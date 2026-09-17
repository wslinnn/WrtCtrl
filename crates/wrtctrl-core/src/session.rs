//! session.rs — 会话生命周期：两段式登录、探针、预检重登、重连。
//!
//! 会话生命周期语义：
//! 登录/预检重登/重连语义：
//! - 两段式登录：先以全 0 临时会话调 session.login，成功取 ubus_rpc_session
//! - session 预检（远程跨网场景关键）：apply 与 confirm 之间 session 过期会导致
//!   confirm 失败 → 进入 rollback 被回滚，故 commit 前必须 system.board 探针验证，
//!   失效（ubus 6/超时/网络）则用存储凭证静默重登
//! - 凭证错误在 rpcd 表现为 result[0]=6（Permission denied）

use crate::error::UbusError;
use crate::rpc::{RouterClient, EMPTY_SESSION};
use std::time::Duration;

/// system.board 探针超时
pub const PROBE_TIMEOUT: Duration = Duration::from_secs(3);
/// 登录超时 5s
pub const LOGIN_TIMEOUT: Duration = Duration::from_secs(5);

#[derive(Debug, thiserror::Error)]
pub enum LoginError {
    /// rpcd 返回非 0，携带 ubus 码
    #[error("auth failed (ubus {0})")]
    Auth(i64),

    #[error("timeout")]
    Timeout,

    /// TLS 证书问题（自签场景），与普通网络错误区分供 UI 给不同引导
    #[error("certificate: {0}")]
    Certificate(String),

    #[error("network: {0}")]
    Network(String),

    #[error("invalid response: {0}")]
    InvalidResponse(String),

    #[error("no device configured")]
    NoDevice,
}

impl From<UbusError> for LoginError {
    fn from(e: UbusError) -> Self {
        match e {
            UbusError::Ubus(code) => Self::Auth(code),
            UbusError::Timeout => Self::Timeout,
            UbusError::NoDevice => Self::NoDevice,
            UbusError::InvalidResponse(m) => Self::InvalidResponse(m),
            // 传输层错误细分：证书问题单独归类（对应旧 errMsg 字符串嗅探的意图，
            // 但走类型化路径）
            UbusError::Network(m)
                if m.to_lowercase().contains("certificate")
                    || m.to_lowercase().contains("invalid peer certificate") =>
            {
                Self::Certificate(m)
            }
            UbusError::Network(m) => Self::Network(m),
            UbusError::InvalidArgument(m) => Self::InvalidResponse(m),
        }
    }
}

impl RouterClient {
    /// 两段式登录：用当前上下文的凭证调 session.login，成功取 ubus_rpc_session
    /// 并就地更新会话。
    /// 登录强制以全 0 临时会话发起（旧 loginDevice 的 `sysauth: null`）——
    /// 带残留旧会话去登录是未定义行为。
    pub async fn login(&self) -> Result<String, LoginError> {
        let (url, credentials) = {
            let guard = self.session.read().await;
            let device = guard.as_ref().ok_or(LoginError::NoDevice)?;
            let url = format!("{}/ubus", device.base_url.trim_end_matches('/'));
            let credentials = (device.username.clone(), device.password.clone());
            (url, credentials)
        };
        let (username, password) = credentials;
        let payload = self
            .post_ubus(
                &url,
                EMPTY_SESSION,
                "session",
                "login",
                serde_json::json!({"username": username, "password": password}),
                LOGIN_TIMEOUT,
            )
            .await
            .map_err(LoginError::from)?;
        let session = payload
            .get("ubus_rpc_session")
            .and_then(|v| v.as_str())
            .ok_or_else(|| {
                LoginError::InvalidResponse("login ok but no ubus_rpc_session".into())
            })?
            .to_string();
        self.update_session_id(&session).await;
        Ok(session)
    }

    /// 探针：system.board（3s）。仅判断会话/连通性，不关心载荷。
    pub async fn probe(&self) -> Result<(), UbusError> {
        self.call_ubus("system", "board", serde_json::json!({}), PROBE_TIMEOUT)
            .await
            .map(|_| ())
    }

    /// commit 前置预检：探针失败 → 用存储凭证静默重登。
    /// 防"apply 成功但 confirm 因 session 过期失败 → 被 120s 回滚"。
    /// 有意偏离说明：重登失败仍继续 apply 的路径已移除
    /// （apply 随后以 ubus 6 失败）；新实现提前以明确错误中止。两者都不会产生
    /// "apply 成功但 confirm 失败"的回滚窗口，新路径错误更清晰、不做无谓 apply。
    pub async fn ensure_session(&self) -> Result<(), LoginError> {
        if self.probe().await.is_ok() {
            return Ok(());
        }
        self.login().await.map(|_| ())
    }

    /// 重连：探活当前会话，失败则静默重登。
    /// 成功返回有效 session id；全部失败返回最后的登录错误。
    pub async fn reconnect(&self) -> Result<String, LoginError> {
        if self.probe().await.is_ok() {
            return Ok(self.current_session_id().await);
        }
        self.login().await
    }
}
