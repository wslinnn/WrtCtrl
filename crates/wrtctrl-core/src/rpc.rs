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
    http: reqwest::Client,
    session: Arc<RwLock<Option<DeviceSession>>>,
}

impl RouterClient {
    /// accept_invalid_certs：自签 HTTPS 场景（按设备配置；对应旧 x-uniauth 拦截器语义）
    pub fn new(accept_invalid_certs: bool) -> Self {
        let http = reqwest::Client::builder()
            .danger_accept_invalid_certs(accept_invalid_certs)
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
    /// `timeout` 为硬超时：请求任何阶段超时即失败，防页面级永久挂起。
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
        let body = json!({
            "jsonrpc": "2.0",
            "id": 1,
            "method": "call",
            "params": [session, object, method, params],
        });
        let response = tokio::time::timeout(timeout, self.http.post(&url).json(&body).send())
            .await
            .map_err(|_| UbusError::Timeout)?
            .map_err(|e| UbusError::Network(e.to_string()))?;
        let status = response.status();
        let payload: Value = response
            .json()
            .await
            .map_err(|e| UbusError::InvalidResponse(format!("http {status}: {e}")))?;
        let result = payload
            .get("result")
            .and_then(|r| r.as_array())
            .ok_or_else(|| UbusError::InvalidResponse(format!("missing result array: http {status}")))?;
        let code = result
            .first()
            .and_then(|v| v.as_i64())
            .ok_or_else(|| UbusError::InvalidResponse("result[0] is not a number".into()))?;
        if code != 0 {
            return Err(UbusError::Ubus(code));
        }
        Ok(result.get(1).cloned().unwrap_or(Value::Null))
    }
}
