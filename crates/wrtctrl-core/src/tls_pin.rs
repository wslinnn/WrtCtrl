//! tls_pin.rs — HTTPS TOFU 证书指纹探针。
//!
//! 背景：reqwest 会话客户端对 https 目标放行任意证书（rpc.rs 的
//! danger_accept_invalid_certs，rpcd 自签生态的现实取舍）——同 LAN 的 MITM
//! 可出示任意证书完成握手，登录体里的 root 密码明文可读。TOFU（Trust On
//! First Use）补上这道防线：与目标做一次性 TLS 握手捕获叶证书 SHA-256 指纹；
//! 首连由上层持久化，后续登录必须与记录一致，不一致即硬错误。
//!
//! 语义约定：
//! - 指纹锚定整张叶证书 DER（非 SPKI）：证书续期/换发即触发不匹配。LAN 路由器
//!   证书极少续期，保守方向可接受；重置途径 = 删除设备重新添加
//! - 传输层失败（DNS/TCP/握手/超时）fail-open：可达性问题由登录流程自身报错，
//!   指纹检查只对「握手成功但证书与记录不符」fail-closed

use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::SignatureScheme;
use sha2::{Digest, Sha256};
use std::sync::Arc;
use std::time::Duration;
use tokio::net::TcpStream;

/// 指纹探针超时：单地址 DNS 后 TCP 连接 + TLS 握手整体
pub const PROBE_TIMEOUT: Duration = Duration::from_secs(5);

/// 传输层探针失败（不构成证书问题）
#[derive(Debug, thiserror::Error)]
#[error("tls probe failed: {0}")]
pub struct TlsProbeError(String);

impl From<std::io::Error> for TlsProbeError {
    fn from(e: std::io::Error) -> Self {
        Self(e.to_string())
    }
}

impl From<rustls::Error> for TlsProbeError {
    fn from(e: rustls::Error) -> Self {
        Self(e.to_string())
    }
}

/// 放行一切证书的验证器：指纹比对是唯一信任锚，这里放行只为捕获证书
#[derive(Debug)]
struct AcceptAny;

impl ServerCertVerifier for AcceptAny {
    fn verify_server_cert(
        &self,
        _end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &rustls::DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn verify_tls13_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &rustls::DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        vec![
            SignatureScheme::RSA_PKCS1_SHA256,
            SignatureScheme::RSA_PKCS1_SHA384,
            SignatureScheme::RSA_PKCS1_SHA512,
            SignatureScheme::ECDSA_NISTP256_SHA256,
            SignatureScheme::ECDSA_NISTP384_SHA384,
            SignatureScheme::ED25519,
            SignatureScheme::RSA_PSS_SHA256,
            SignatureScheme::RSA_PSS_SHA384,
            SignatureScheme::RSA_PSS_SHA512,
        ]
    }
}

/// 叶证书 DER 的 SHA-256，形如 "sha256:<64hex>"
pub fn fingerprint_of(cert: &CertificateDer<'_>) -> String {
    let digest = Sha256::digest(cert.as_ref());
    let hex: String = digest.iter().map(|b| format!("{b:02x}")).collect();
    format!("sha256:{hex}")
}

/// 与目标做一次性 TLS 握手，返回叶证书指纹。传输层任何失败归 TlsProbeError
/// （多地址逐个尝试，取最后一个错误）
pub async fn leaf_fingerprint(base_url: &str) -> Result<String, TlsProbeError> {
    let rest = base_url
        .strip_prefix("https://")
        .ok_or_else(|| TlsProbeError("not an https base url".into()))?;
    let host_port = rest.split('/').next().unwrap_or(rest);
    // 主机/端口拆分（IPv6 字面量带方括号），缺省端口 443
    let (host, port) = if let Some(inner) = host_port.strip_prefix('[') {
        let host = inner.split(']').next().unwrap_or(inner).to_string();
        let port = host_port
            .split("]:")
            .nth(1)
            .and_then(|p| p.parse().ok())
            .unwrap_or(443u16);
        (host, port)
    } else {
        let mut parts = host_port.splitn(2, ':');
        let host = parts.next().unwrap_or_default().to_string();
        let port = parts.next().and_then(|p| p.parse().ok()).unwrap_or(443u16);
        (host, port)
    };
    if host.is_empty() {
        return Err(TlsProbeError("empty host".into()));
    }
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let config = rustls::ClientConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()?
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(AcceptAny))
        .with_no_client_auth();
    let connector = tokio_rustls::TlsConnector::from(Arc::new(config));
    let server_name = ServerName::try_from(host.clone())
        .map_err(|e| TlsProbeError(e.to_string()))?;
    let addrs = tokio::net::lookup_host((host.as_str(), port))
        .await
        .map_err(|e| TlsProbeError(format!("dns: {e}")))?;
    let mut last_err: Option<TlsProbeError> = None;
    for addr in addrs {
        let attempt = async {
            let tcp = TcpStream::connect(addr).await?;
            let tls = connector.connect(server_name.clone(), tcp).await?;
            let (_, session) = tls.get_ref();
            session
                .peer_certificates()
                .and_then(|certs| certs.first())
                .map(fingerprint_of)
                .ok_or_else(|| TlsProbeError("no peer certificate".into()))
        };
        match tokio::time::timeout(PROBE_TIMEOUT, attempt).await {
            Ok(Ok(fp)) => return Ok(fp),
            Ok(Err(e)) => last_err = Some(TlsProbeError(e.to_string())),
            Err(_) => last_err = Some(TlsProbeError("timeout".into())),
        }
    }
    Err(last_err.unwrap_or_else(|| TlsProbeError("no address resolved".into())))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 指纹格式与摘要算法锚定：sha256("") 的已知向量
    #[test]
    fn fingerprint_known_vector() {
        let cert = CertificateDer::from(Vec::new());
        assert_eq!(
            fingerprint_of(&cert),
            "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );
    }

    #[test]
    fn fingerprint_hex_is_lowercase_64() {
        let cert = CertificateDer::from(vec![0u8, 1, 2, 3]);
        let fp = fingerprint_of(&cert);
        assert!(fp.starts_with("sha256:"));
        let hex = fp.trim_start_matches("sha256:");
        assert_eq!(hex.len(), 64);
        assert!(hex.chars().all(|c| c.is_ascii_hexdigit() && !c.is_ascii_uppercase()));
    }

    #[test]
    fn non_https_base_url_rejected() {
        // 探针只服务 https；http 目标由调用方短路，不走此路径
        let rt = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        let err = rt
            .block_on(leaf_fingerprint("http://192.168.1.1"))
            .unwrap_err();
        assert!(err.to_string().contains("not an https base url"));
    }
}
