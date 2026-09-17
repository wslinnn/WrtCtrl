//! ping.rs — 设备 HTTP 探活（对应旧 DeviceManager.pingDevice/pingLevel）。
//!
//! 轻量 GET 路由器根 URL（自签证书由 RouterClient 的 rustls 配置统一处理），
//! 返回往返毫秒；失败/超时 None。5s 超时。

use crate::rpc::RouterClient;
use serde::Serialize;
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
    /// HTTP 探活任意设备根 URL（设备列表页并行 ping 多台用）；离线/超时 → None。
    /// 语义对齐旧 pingDevice：收到任意 HTTP 响应（含 302/404）即算可达，不检查状态码。
    pub async fn ping_url(&self, base_url: &str) -> Option<u64> {
        let url = format!("{}/", base_url.trim_end_matches('/'));
        let start = Instant::now();
        let fut = self.http.get(&url).send();
        tokio::time::timeout(PING_TIMEOUT, fut).await.ok()?.ok()?;
        Some(start.elapsed().as_millis() as u64)
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
}
