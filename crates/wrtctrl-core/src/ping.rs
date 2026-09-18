//! ping.rs — 设备探活（对应旧 DeviceManager.pingDevice/pingLevel）。
//!
//! 优先 ICMP（/system/bin/ping，rtt 真实）；实测表明部分 ROM
//! 限制 app 执行该二进制（路径/cap_net_raw/SELinux），故 ICMP 失败回落 HTTP HEAD
//! （收到任意响应即可达，数值含握手开销偏大，仅作保底）。

use crate::rpc::RouterClient;
use serde::Serialize;
use std::process::Command;
use std::time::{Duration, Instant};

const PING_TIMEOUT: Duration = Duration::from_secs(5);

#[derive(Debug, Clone, Copy, Serialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum PingLevel {
    Fast,
    Ok,
    Slow,
}

/// ms → 延迟档位：<100 fast / <300 ok / ≥300 slow（None=offline，与 LuCI 同款分档）
pub fn ping_level(ms: u64) -> PingLevel {
    if ms < 100 {
        PingLevel::Fast
    } else if ms < 300 {
        PingLevel::Ok
    } else {
        PingLevel::Slow
    }
}

impl RouterClient {
    /// 探活任意设备根 URL（设备列表页并行 ping 多台用）
    pub async fn ping_url(&self, base_url: &str) -> Option<u64> {
        if let Some(host) = host_of(base_url) {
            if let Some(ms) = icmp_ping(&host).await {
                return Some(ms);
            }
        }
        http_probe(self, base_url).await
    }

    /// 探活当前设备
    pub async fn ping_device(&self) -> Option<u64> {
        let base = self
            .session
            .read()
            .await
            .as_ref()
            .map(|d| d.base_url.clone())?;
        self.ping_url(&base).await
    }
}

/// 从 baseUrl 提取主机名（去 scheme/端口/路径；IPv6 字面量去方括号）
fn host_of(base_url: &str) -> Option<String> {
    let rest = base_url.split("://").nth(1).unwrap_or(base_url);
    let host_port = rest.split('/').next()?;
    if let Some(inner) = host_port.strip_prefix('[') {
        return Some(inner.split(']').next()?.to_string());
    }
    let host = host_port.split(':').next()?;
    (!host.is_empty()).then(|| host.to_string())
}

/// ICMP 探活：ping -c1 -W2（阻塞执行放 spawn_blocking，rtt 从输出解析）。
/// 不写死绝对路径、按 PATH 解析：部分 ROM 的 ping 不在 /system/bin（写死会导致恒失败）。
async fn icmp_ping(host: &str) -> Option<u64> {
    let host = host.to_string();
    let start = Instant::now();
    let output = tokio::task::spawn_blocking(move || {
        Command::new("ping")
            .args(["-c", "1", "-W", "2", &host])
            .output()
            .ok()
    })
    .await
    .ok()??;
    if !output.status.success() {
        return None;
    }
    let stdout = String::from_utf8_lossy(&output.stdout);
    let parsed = stdout
        .split("time=")
        .nth(1)
        .and_then(|rest| rest.split_whitespace().next())
        .and_then(|s| s.parse::<f64>().ok())
        .map(|ms| ms.round() as u64);
    Some(parsed.unwrap_or_else(|| start.elapsed().as_millis() as u64))
}

/// HTTP HEAD 探活（ICMP 不可用时的兜底；uhttpd 支持 HEAD）
async fn http_probe(client: &RouterClient, base_url: &str) -> Option<u64> {
    let url = format!("{}/", base_url.trim_end_matches('/'));
    let start = Instant::now();
    client.http_head_ok(&url, PING_TIMEOUT).await.ok()?;
    Some(start.elapsed().as_millis() as u64)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn level_boundaries() {
        assert_eq!(ping_level(0), PingLevel::Fast);
        assert_eq!(ping_level(99), PingLevel::Fast);
        assert_eq!(ping_level(100), PingLevel::Ok);
        assert_eq!(ping_level(299), PingLevel::Ok);
        assert_eq!(ping_level(300), PingLevel::Slow);
        assert_eq!(ping_level(5000), PingLevel::Slow);
    }

    #[test]
    fn host_extraction() {
        assert_eq!(host_of("http://192.168.1.1/"), Some("192.168.1.1".into()));
        assert_eq!(host_of("https://192.168.1.1:8443/cgi"), Some("192.168.1.1".into()));
        assert_eq!(host_of("http://[fd00::1]:8080/"), Some("fd00::1".into()));
        assert_eq!(host_of("192.168.1.1"), Some("192.168.1.1".into()));
        assert_eq!(host_of("http:///path"), None);
    }
}
