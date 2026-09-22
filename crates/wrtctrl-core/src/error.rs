//! 统一错误模型：JNI 边界按此序列化为 {code, message}，UI 层对 ubus 码有分支逻辑
//! （如 6=Permission denied 触发重新认证提示）。

use thiserror::Error;

#[derive(Debug, Error)]
pub enum UbusError {
    #[error("network: {0}")]
    Network(String),

    /// 硬超时兜底触发。语义对齐外层请求超时：
    /// "连接已建立但服务端不响应"（如 rpcd file.exec 执行 hang 的命令）也必须最终失败
    #[error("timeout")]
    Timeout,

    /// rpcd 返回 result[0] != 0，原样透传 ubus 错误码
    /// （2=INVALID_ARGUMENT、6=Permission denied、11/12=超时类）
    #[error("ubus error {0}")]
    Ubus(i64),

    #[error("invalid response: {0}")]
    InvalidResponse(String),

    /// 本地校验拒绝（如 apply 白名单不匹配）
    #[error("invalid argument: {0}")]
    InvalidArgument(String),

    #[error("no device configured")]
    NoDevice,
}

impl From<serde_json::Error> for UbusError {
    fn from(e: serde_json::Error) -> Self {
        UbusError::InvalidResponse(format!("json: {e}"))
    }
}

/// 传输层错误链子分类：reqwest 的 Display 只有最外层，真实原因（DNS 失败/连接拒绝/
/// 证书/TLS）在 source 链里（由 rpc::error_chain 展开）。JNI 信封与 LoginError
/// 转换共用此单一实现——此前两处各自嗅探关键词且 token 集不一致，reqwest 文案
/// 演进时会不同步。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NetFailureKind {
    /// 域名解析失败（典型：域名仅 IPv6 而当前网络无 v6）
    Dns,
    /// 连接被拒绝（端口未开放/防火墙 REJECT）
    Refused,
    /// TLS 握手层失败（自签证书等）
    Tls,
    Other,
}

pub fn classify_network_chain(chain: &str) -> NetFailureKind {
    let lower = chain.to_lowercase();
    if lower.contains("dns error")
        || lower.contains("failed to lookup address")
        || lower.contains("no address associated")
    {
        NetFailureKind::Dns
    } else if lower.contains("refused") {
        NetFailureKind::Refused
    } else if lower.contains("certificate")
        || lower.contains("tls")
        // "alert" 单词过宽（任何含 alert 的错误链都会误判为 TLS），须与
        // handshake 同现才归类（TLS alert 报文的标准文案是 "tls handshake alert"）
        || (lower.contains("alert") && lower.contains("handshake"))
    {
        NetFailureKind::Tls
    } else {
        NetFailureKind::Other
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn network_chain_classification() {
        assert_eq!(
            classify_network_chain("error sending request: dns error: failed to lookup address"),
            NetFailureKind::Dns
        );
        assert_eq!(
            classify_network_chain("error: no address associated with hostname"),
            NetFailureKind::Dns
        );
        assert_eq!(
            classify_network_chain("Connection refused (os error 111)"),
            NetFailureKind::Refused
        );
        assert_eq!(
            classify_network_chain("invalid peer certificate: CertificateExpired"),
            NetFailureKind::Tls
        );
        assert_eq!(classify_network_chain("tls handshake alert"), NetFailureKind::Tls);
        assert_eq!(classify_network_chain("connection timed out"), NetFailureKind::Other);
    }
}

/// 会话层错误 → ubus 错误的反向映射（如 commit 预检中重登失败：
/// Auth 还原为原始 ubus 码透传给 UI，与"预检前就是 6"的表现一致）
impl From<crate::session::LoginError> for UbusError {
    fn from(e: crate::session::LoginError) -> Self {
        use crate::session::LoginError as L;
        match e {
            L::Auth(code) => UbusError::Ubus(code),
            L::Timeout => UbusError::Timeout,
            L::NoDevice => UbusError::NoDevice,
            L::Certificate(m) | L::Network(m) => UbusError::Network(m),
            // TOFU 指纹不一致：走 Network/Tls 分类通道，文案自带 expected/actual
            L::CertMismatch { actual, .. } => UbusError::Network(actual),
            L::InvalidResponse(m) => UbusError::InvalidResponse(m),
        }
    }
}
