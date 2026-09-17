//! rpc.rs — ubus JSON-RPC 通道。
//!
//! ubus JSON-RPC 通道语义：
//! - 所有路由器 IO 的唯一入口；ubus result 约定：result[0]===0 成功，result[1] 为数据载荷
//! - 硬超时兜底：请求的 timeout 对"连接已建立但服务端不响应"（如 rpcd file.exec
//!   执行 hang 的命令）可能不生效，用 tokio::time::timeout 保证最终失败
//! - session 走 JSON-RPC params[0]，不是 cookie，无需 cookie store

use crate::error::UbusError;
use serde_json::{json, Value};
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::RwLock;

/// rpcd session.login 用的临时会话（全 0），登录成功后换 ubus_rpc_session
pub const EMPTY_SESSION: &str = "00000000000000000000000000000000";

/// 设备会话上下文（设备列表持久化在 Kotlin 层）
#[derive(Debug, Clone)]
pub struct DeviceSession {
    /// 形如 http://192.168.1.1:80、https://router.lan:443（不含 /ubus 路径）
    pub base_url: String,
    /// ubus_rpc_session；None 表示未登录（以 EMPTY_SESSION 调用，仅用于 login）
    pub session: Option<String>,
    pub username: String,
    pub password: String,
}

/// 路由器客户端：持有 HTTP 客户端与当前设备会话
pub struct RouterClient {
    pub(crate) http: reqwest::Client,
    pub(crate) session: Arc<RwLock<Option<DeviceSession>>>,
}

/// 错误因果链展开：reqwest 的 Display 只有最外层"error sending request for url"，
/// 真正原因（DNS 失败/连接拒绝/证书/TLS 版本）在 source() 链里——全部翻出来，
/// 否则用户看到错误也不知道为什么。
pub(crate) fn error_chain(e: &dyn std::error::Error) -> String {
    let mut msg = e.to_string();
    let mut source = e.source();
    while let Some(cause) = source {
        msg.push_str(" ← ");
        msg.push_str(&cause.to_string());
        source = cause.source();
    }
    msg
}

impl RouterClient {
    /// accept_invalid_certs：自签 HTTPS 场景（按设备配置；对应旧 x-uniauth 拦截器语义）。
    /// no_proxy：路由器是 LAN/VPN 直连目标，绝不能走系统代理——
    /// 会把路由器凭证经第三方代理转发，且代理故障会伪装成路由器故障。
    pub fn new(accept_invalid_certs: bool) -> Self {
        let http = reqwest::Client::builder()
            .danger_accept_invalid_certs(accept_invalid_certs)
            .no_proxy()
            .build()
            .expect("reqwest client build failed");
        Self {
            http,
            session: Arc::new(RwLock::new(None)),
        }
    }

    /// 设置/切换当前设备（切设备语义：整体替换会话；调用方负责清理上层缓存）
    pub async fn set_session(&self, session: Option<DeviceSession>) {
        *self.session.write().await = session;
    }

    pub async fn session(&self) -> Option<DeviceSession> {
        self.session.read().await.clone()
    }

    /// 通用 ubus call（任意 object/method），成功返回 result[1] 载荷。
    /// `timeout` 为硬超时：覆盖 发送+响应头+响应体 全程——"连接已建立但服务端
    /// 不响应/半途挂起"（如 rpcd file.exec 执行 hang 的命令）都必须最终失败。
    pub async fn call_ubus(
        &self,
        object: &str,
        method: &str,
        params: Value,
        timeout: Duration,
    ) -> Result<Value, UbusError> {
        let (url, session) = {
            let guard = self.session.read().await;
            let device = guard.as_ref().ok_or(UbusError::NoDevice)?;
            let url = format!("{}/ubus", device.base_url.trim_end_matches('/'));
            let session = device
                .session
                .clone()
                .unwrap_or_else(|| EMPTY_SESSION.to_string());
            (url, session)
        };
        self.post_ubus(&url, &session, object, method, params, timeout)
            .await
    }

    /// 底层 POST：显式指定会话值（login 强制用 EMPTY_SESSION 走此路径，
    /// 对应旧 loginDevice 的 `sysauth: null`——绝不能带旧会话去登录）。
    /// 硬超时覆盖 send + body 读取全程。
    pub(crate) async fn post_ubus(
        &self,
        url: &str,
        session: &str,
        object: &str,
        method: &str,
        params: Value,
        timeout: Duration,
    ) -> Result<Value, UbusError> {
        let body = json!({
            "jsonrpc": "2.0",
            "id": 1,
            "method": "call",
            "params": [session, object, method, params],
        });
        let fut = async {
            let response = self
                .http
                .post(url)
                .json(&body)
                .send()
                .await
                .map_err(|e| UbusError::Network(error_chain(&e)))?;
            let status = response.status();
            let payload: Value = response
                .json()
                .await
                .map_err(|e| UbusError::InvalidResponse(format!("http {status}: {e}")))?;
            Ok::<_, UbusError>((status, payload))
        };
        let (status, payload) = tokio::time::timeout(timeout, fut)
            .await
            .map_err(|_| UbusError::Timeout)??;
        let result = payload
            .get("result")
            .and_then(|r| r.as_array())
            .ok_or_else(|| {
                UbusError::InvalidResponse(format!("missing result array: http {status}"))
            })?;
        let code = result
            .first()
            .and_then(|v| v.as_i64())
            .ok_or_else(|| UbusError::InvalidResponse("result[0] is not a number".into()))?;
        if code != 0 {
            return Err(UbusError::Ubus(code));
        }
        Ok(result.get(1).cloned().unwrap_or(Value::Null))
    }

    /// 就地更新会话 id（login 成功后调用；无设备上下文时静默忽略）
    pub(crate) async fn update_session_id(&self, id: &str) {
        if let Some(device) = self.session.write().await.as_mut() {
            device.session = Some(id.to_string());
        }
    }

    /// 当前会话 id（未登录返回全 0 临时会话，与 call_ubus 的兜底一致）
    pub async fn current_session_id(&self) -> String {
        self.session
            .read()
            .await
            .as_ref()
            .and_then(|d| d.session.clone())
            .unwrap_or_else(|| EMPTY_SESSION.to_string())
    }
}
