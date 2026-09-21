//! ping.rs — 设备探活。
//!
//! 优先 ICMP（/system/bin/ping，rtt 真实）；实测表明部分 ROM
//! 限制 app 执行该二进制（路径/cap_net_raw/SELinux），故 ICMP 失败回落 HTTP HEAD
//! （收到任意响应即可达，数值含握手开销偏大，仅作保底）。
//! 延迟分档（<100/<300）由 Kotlin 侧呈现层实现——分档纯 UI 语义，不跨 FFI。

use crate::rpc::RouterClient;
use std::process::Command;
use std::time::{Duration, Instant};

const PING_TIMEOUT: Duration = Duration::from_secs(5);

/// ICMP 子进程整体硬超时：`-W 2` 只约束单包等待，域名解析与个别 ROM 的
/// 异常 ping 不受其控——无兜底时 spawn_blocking 线程可被无限占用（阻塞
/// JNI 调用线程）。超时后子进程成为孤儿由其自身退出释放，本调用按不可达处理
const ICMP_PING_TIMEOUT: Duration = Duration::from_secs(10);

impl RouterClient {
    /// 探活任意设备根 URL（设备列表页并行 ping 多台用；当前设备探活同样
    /// 经此以 baseUrl 发起——ping_device 包装无消费者已删）
    pub async fn ping_url(&self, base_url: &str) -> Option<u64> {
        if let Some(host) = host_of(base_url) {
            if let Some(ms) = icmp_ping(&host).await {
                return Some(ms);
            }
        }
        http_probe(self, base_url).await
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

/// ICMP 探活：ping -c1 -W2，整体套 10s 硬超时（阻塞执行放 spawn_blocking，rtt 从输出解析）。
/// 不写死绝对路径、按 PATH 解析：部分 ROM 的 ping 不在 /system/bin（写死会导致恒失败）。
async fn icmp_ping(host: &str) -> Option<u64> {
    let host = host.to_string();
    let start = Instant::now();
    let joined =
        tokio::time::timeout(ICMP_PING_TIMEOUT, tokio::task::spawn_blocking(move || {
            Command::new("ping")
                .args(["-c", "1", "-W", "2", &host])
                .output()
                .ok()
        }))
        .await;
    // 硬超时 / JoinError / 进程启动失败：一律按不可达处理（孤儿子进程自行退出释放线程）
    let output = joined.ok()?.ok()??;
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
    fn host_extraction() {
        assert_eq!(host_of("http://192.168.1.1/"), Some("192.168.1.1".into()));
        assert_eq!(host_of("https://192.168.1.1:8443/cgi"), Some("192.168.1.1".into()));
        assert_eq!(host_of("http://[fd00::1]:8080/"), Some("fd00::1".into()));
        assert_eq!(host_of("192.168.1.1"), Some("192.168.1.1".into()));
        assert_eq!(host_of("http:///path"), None);
    }
}
