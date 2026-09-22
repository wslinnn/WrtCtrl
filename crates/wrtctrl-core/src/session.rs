//! session.rs — 会话生命周期：两段式登录、探针、预检重登、重连。
//!
//! 会话生命周期语义：
//! 登录/预检重登/重连语义：
//! - 两段式登录：先以全 0 临时会话调 session.login，成功取 ubus_rpc_session
//! - session 预检（远程跨网场景关键）：apply 与 confirm 之间 session 过期会导致
//!   confirm 失败 → 进入 rollback 被回滚，故 commit 前必须 system.board 探针验证，
//!   失效（ubus 6/超时/网络）则用存储凭证静默重登
//! - 凭证错误在 rpcd 表现为 result[0]=6（Permission denied）

use crate::error::{classify_network_chain, NetFailureKind, UbusError};
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

    /// TOFU 指纹不一致：当前证书与首连记录不符——MITM 或路由器证书已更换
    #[error("certificate mismatch: expected {expected}, got {actual}")]
    CertMismatch { expected: String, actual: String },

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
            // 传输层错误细分：证书/TLS 问题单独归类（对应旧 errMsg 字符串嗅探的意图，
            // 但走类型化路径；token 集与 JNI 信封共用 error::classify_network_chain）
            UbusError::Network(m) => match classify_network_chain(&m) {
                NetFailureKind::Tls => Self::Certificate(m),
                _ => Self::Network(m),
            },
            UbusError::InvalidArgument(m) => Self::InvalidResponse(m),
        }
    }
}

impl std::fmt::Debug for LoginOutcome {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("LoginOutcome")
            .field("session", &"[redacted]")
            .field("cert_sha256", &self.cert_sha256)
            .finish()
    }
}

/// 登录结果：会话 id + TOFU 叶证书指纹（https 且捕获成功时非 None——
/// 首连由上层持久化；重连路径不经此结构回传）
#[derive(Clone)]
pub struct LoginOutcome {
    pub session: String,
    pub cert_sha256: Option<String>,
}

impl RouterClient {
    /// 两段式登录：用当前上下文的凭证调 session.login，成功取 ubus_rpc_session
    /// 并就地更新会话。
    /// 登录强制以全 0 临时会话发起（旧 loginDevice 的 `sysauth: null`）——
    /// 带残留旧会话去登录是未定义行为。
    /// TOFU：https 目标登录前先做一次性握手比对叶证书指纹——
    /// 有记录且不一致 = 硬错误（MITM 或证书已更换）；无记录 = 随登录结果
    /// 带回捕获值由上层持久化；探针传输失败 fail-open（可达性由登录自身报错）。
    pub async fn login(&self) -> Result<LoginOutcome, LoginError> {
        let (url, base_url, credentials, expected_fp) = {
            let guard = self.session.read().await;
            let device = guard.as_ref().ok_or(LoginError::NoDevice)?;
            let url = format!("{}/ubus", device.base_url.trim_end_matches('/'));
            let credentials = (device.username.clone(), device.password.clone());
            let expected = device.expected_cert_sha256.clone();
            (url, device.base_url.clone(), credentials, expected)
        };
        let mut observed_fp: Option<String> = None;
        if base_url.starts_with("https://") {
            match crate::tls_pin::leaf_fingerprint(&base_url).await {
                Ok(fp) => {
                    if let Some(expected) = &expected_fp {
                        if expected != &fp {
                            return Err(LoginError::CertMismatch {
                                expected: expected.clone(),
                                actual: fp,
                            });
                        }
                    }
                    observed_fp = Some(fp);
                }
                // 探针挂了不代表证书错：真实连接错误由 post_ubus 呈现
                Err(_) => {}
            }
        }
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
        self.update_session_id(&base_url, &session).await;
        Ok(LoginOutcome { session, cert_sha256: observed_fp })
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

    /// 重连：探活当前会话，失败则静默重登（重登同样过 TOFU
    /// 指纹校验）。成功返回有效 session；全部失败返回最后的登录错误。
    /// 未登录（session=None）直接走 login：probe 以 EMPTY_SESSION 发起，个别 ACL
    /// 配置会对未认证放行 system.board——那时全 0 临时会话会被误当有效会话返回
    pub async fn reconnect(&self) -> Result<LoginOutcome, LoginError> {
        let logged_in = self
            .session
            .read()
            .await
            .as_ref()
            .is_some_and(|d| d.session.is_some());
        if logged_in && self.probe().await.is_ok() {
            return Ok(LoginOutcome {
                session: self.current_session_id().await,
                cert_sha256: None,
            });
        }
        self.login().await
    }
}
