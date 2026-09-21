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

/// TCP 连接超时（独立于请求硬超时）：连接失败与「已连上但对端不应答」在错误链上
/// 可区分——前者是网络路径/防火墙问题，后者是服务端问题
pub const CONNECT_TIMEOUT: Duration = Duration::from_secs(3);

/// 设备会话上下文（设备列表持久化在 Kotlin 层）
#[derive(Clone)]
pub struct DeviceSession {
    /// 形如 http://192.168.1.1:80、https://router.lan:443（不含 /ubus 路径）
    pub base_url: String,
    /// ubus_rpc_session；None 表示未登录（以 EMPTY_SESSION 调用，仅用于 login）
    pub session: Option<String>,
    pub username: String,
    pub password: String,
}

impl std::fmt::Debug for DeviceSession {
    /// 手写 Debug 脱敏：password/session 永不进日志面（防未来调试日志泄密）
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("DeviceSession")
            .field("base_url", &self.base_url)
            .field("session", &self.session.as_ref().map(|_| "[redacted]"))
            .field("username", &self.username)
            .field("password", &"[redacted]")
            .finish()
    }
}

/// 会话流量 HTTP 客户端构造：TLS 策略与重定向策略集中在此
fn build_http(accept_invalid_certs: bool) -> reqwest::Client {
    reqwest::Client::builder()
        .danger_accept_invalid_certs(accept_invalid_certs)
        // 重定向一律禁用：307/308 会携带原始 body 重发 POST——登录体含密码、
        // 其余调用体含 session token，被劫持 LAN 上一次重定向即凭据收割
        .redirect(reqwest::redirect::Policy::none())
        .no_proxy()
        .connect_timeout(CONNECT_TIMEOUT)
        .build()
        .expect("reqwest client build failed")
}

/// 路由器客户端：持有 HTTP 客户端与当前设备会话
pub struct RouterClient {
    /// 会话流量客户端（含密码/session 的调用）：accept_invalid_certs 由当前设备的
    /// baseUrl scheme 派生（set_session 时重建）——https 目标按自签放行，http 目标无 TLS 层
    pub(crate) http: RwLock<reqwest::Client>,
    /// 探活专用客户端：无凭据 HEAD（ICMP 失败后的可达性兜底），恒放行自签证书——
    /// 探活目标是任意设备（不止当前设备），不能跟随当前设备的 TLS 策略
    pub(crate) probe_http: reqwest::Client,
    pub(crate) session: Arc<RwLock<Option<DeviceSession>>>,
    /// commit 串行化锁：rpcd 的 rollback/confirm 是全局单槽，两个并发 commit 的
    /// apply/confirm 在路由器端交错可能只消耗一次 confirm、另一改动 120s 后被静默
    /// 回滚（uci apply 提交全部 pending，config 参数不隔离）。客户端串行化即可闭合
    pub(crate) commit_lock: tokio::sync::Mutex<()>,
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
    /// accept_invalid_certs：会话流量由 set_session 按设备 baseUrl scheme 派生重建；
    /// 探活流量恒放行自签（probe_http）。构造参数只是初始值（首个设备 set 前的占位）。
    /// no_proxy：路由器是 LAN/VPN 直连目标，绝不能走系统代理——
    /// 会把路由器凭证经第三方代理转发，且代理故障会伪装成路由器故障。
    pub fn new(accept_invalid_certs: bool) -> Self {
        Self {
            http: RwLock::new(build_http(accept_invalid_certs)),
            probe_http: build_http(true),
            session: Arc::new(RwLock::new(None)),
            commit_lock: tokio::sync::Mutex::new(()),
        }
    }

    /// 设置/切换当前设备（切设备语义：整体替换会话；调用方负责清理上层缓存）。
    /// 会话流量的 TLS 策略随设备派生重建（https → 自签放行，http → 无 TLS 层）
    pub async fn set_session(&self, session: Option<DeviceSession>) {
        let accept = session
            .as_ref()
            .map(|d| d.base_url.starts_with("https"))
            .unwrap_or(false);
        *self.http.write().await = build_http(accept);
        *self.session.write().await = session;
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
                .read()
                .await
                .post(url)
                .json(&body)
                .send()
                .await
                .map_err(|e| UbusError::Network(error_chain(&e)))?;
            let status = response.status();
            if !status.is_success() {
                // 非 2xx 一律失败：反代/portal 注入的可解析 JSON 不可信，
                // 不给「500 + 合法 result 数组被当成功」留通道
                return Err(UbusError::InvalidResponse(format!("http {status}")));
            }
            let payload: Value = response
                .json()
                .await
                .map_err(|e| UbusError::InvalidResponse(format!("http {status}: {e}")))?;
            Ok::<_, UbusError>(payload)
        };
        let payload = tokio::time::timeout(timeout, fut)
            .await
            .map_err(|_| UbusError::Timeout)??;
        // JSON-RPC 错误信封（对象/方法不存在、权限拒绝等 rpcd 拒绝路径）没有 result
        // 字段——先于"缺 result 数组"报出真实原因，否则踢人等调用只会得到笼统的
        // invalid response
        if let Some(err) = payload.get("error").and_then(|e| e.as_object()) {
            let code = err.get("code").and_then(|c| c.as_i64()).unwrap_or_default();
            let message = err.get("message").and_then(|m| m.as_str()).unwrap_or("unknown");
            return Err(UbusError::InvalidResponse(format!(
                "jsonrpc error {code}: {message}"
            )));
        }
        let result = payload
            .get("result")
            .and_then(|r| r.as_array())
            .ok_or_else(|| UbusError::InvalidResponse("missing result array".into()))?;
        let code = result
            .first()
            .and_then(|v| v.as_i64())
            .ok_or_else(|| UbusError::InvalidResponse("result[0] is not a number".into()))?;
        if code != 0 {
            return Err(UbusError::Ubus(code));
        }
        Ok(result.get(1).cloned().unwrap_or(Value::Null))
    }

    /// 就地更新会话 id（login 成功后调用；无设备上下文时静默忽略）。
    /// base_url 因果校验：登录的网络往返期间设备可能已被 setDevice 切换——
    /// 旧设备的 session id 绝不能写进新设备的上下文（写错后靠 ubus 6 探针自愈，
    /// 但多一轮失败调用）
    pub(crate) async fn update_session_id(&self, base_url: &str, id: &str) {
        if let Some(device) = self.session.write().await.as_mut() {
            if device.base_url == base_url {
                device.session = Some(id.to_string());
            }
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

    /// HTTP HEAD 探活原语（ping 的兜底路径）：收到任意响应即视为可达，不检查状态码。
    /// 走 probe_http（恒放行自签、禁重定向）：探活目标是任意设备，不跟随当前设备的
    /// TLS 策略；HEAD 无 body，重定向不涉及凭据，但统一禁用保持策略一致
    pub(crate) async fn http_head_ok(&self, url: &str, timeout: Duration) -> Result<(), UbusError> {
        let fut = self.probe_http.head(url).send();
        tokio::time::timeout(timeout, fut)
            .await
            .map_err(|_| UbusError::Timeout)?
            .map(|_| ())
            .map_err(|e| UbusError::Network(error_chain(&e)))
    }
}
