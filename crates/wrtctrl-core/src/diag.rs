//! diag.rs — 网络诊断 ping/traceroute/nslookup。诊断命令语义：
//!
//! 语义说明：
//! - 统一走 ubus file.exec（rpcd 一次性返回整段 stdout，不支持流式）
//! - 强制自终止参数（-c/-w/-m）：ubus exec 无服务端 timeout，仅客户端超时；
//!   不自终止会导致客户端超时后 rpcd 子进程挂起（孤儿进程）
//! - 安全：ubus params 为 argv 数组（不经 shell），天然免疫 shell 注入；
//!   host 额外做字符净化防 argv 意外
//! - 二进制路径对齐 OpenWrt 实测 which + rpcd ACL（luci-mod-network 已授予）：
//!   ping/traceroute/traceroute6 在 /bin/，nslookup 在 /usr/bin/。改路径会
//!   NOT_FOUND 或 PERMISSION_DENIED

use crate::error::UbusError;
use crate::rpc::RouterClient;
use serde_json::json;
use std::time::Duration;

/// busybox nslookup 无 deadline 形参，静默 DNS 触发内部重试(~2×5s)，
/// 客户端 timeout 需覆盖该周期，避免先超时留下 rpcd 孤儿子进程
pub const NSLOOKUP_TIMEOUT: Duration = Duration::from_secs(25);

#[derive(Debug, Clone, serde::Serialize)]
pub struct ExecResult {
    pub code: Option<i64>,
    pub stdout: String,
    pub stderr: String,
}

/// host 净化：仅允许 IP(v4/v6,含 zone-id)/域名字符，防 argv 级意外
fn clean_host(host: &str) -> Result<String, UbusError> {
    let h = host.trim();
    if h.is_empty() {
        return Err(UbusError::InvalidArgument("empty host".into()));
    }
    if !h
        .chars()
        .all(|c| c.is_ascii_alphanumeric() || "._:%-".contains(c))
    {
        return Err(UbusError::InvalidArgument(format!("invalid host: {h}")));
    }
    Ok(h.to_string())
}

impl RouterClient {
    /// 通用 exec：返回 {code, stdout, stderr}（与 LuCI 行为一致）
    pub async fn exec(
        &self,
        bin: &str,
        args: &[&str],
        timeout: Duration,
    ) -> Result<ExecResult, UbusError> {
        let payload = self
            .call_ubus(
                "file",
                "exec",
                json!({"command": bin, "params": args, "env": null}),
                timeout,
            )
            .await?;
        Ok(ExecResult {
            code: payload.get("code").and_then(|v| v.as_i64()),
            stdout: payload
                .get("stdout")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string(),
            stderr: payload
                .get("stderr")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string(),
        })
    }

    /// ping：count 次数、wait 单包等待(s)、deadline 整体上限(s)；0 值回落默认（JS `||` 语义）
    pub async fn ping(
        &self,
        host: &str,
        count: u32,
        wait: u32,
        deadline: u32,
    ) -> Result<ExecResult, UbusError> {
        let host = clean_host(host)?;
        let count = if count == 0 { 4 } else { count };
        let wait = if wait == 0 { 2 } else { wait };
        let deadline = if deadline == 0 { 8 } else { deadline };
        let timeout = Duration::from_millis(deadline as u64 * 1000 + 4000);
        self.exec(
            "/bin/ping",
            &[
                "-c",
                &count.to_string(),
                "-W",
                &wait.to_string(),
                "-w",
                &deadline.to_string(),
                &host,
            ],
            timeout,
        )
        .await
    }

    /// traceroute：maxHops 跳数、wait 每跳超时(s)、queries 每跳探测数。
    /// IPv6 目标（含 :）走 traceroute6
    pub async fn traceroute(
        &self,
        host: &str,
        max_hops: u32,
        wait: u32,
        queries: u32,
    ) -> Result<ExecResult, UbusError> {
        let host = clean_host(host)?;
        let max_hops = if max_hops == 0 { 15 } else { max_hops };
        let wait = if wait == 0 { 1 } else { wait };
        let queries = if queries == 0 { 1 } else { queries };
        let bin = if host.contains(':') {
            "/bin/traceroute6"
        } else {
            "/bin/traceroute"
        };
        let est = max_hops as u64 * wait as u64 * queries as u64;
        let timeout = Duration::from_millis(est * 1000 + 5000);
        self.exec(
            bin,
            &[
                "-m",
                &max_hops.to_string(),
                "-w",
                &wait.to_string(),
                "-q",
                &queries.to_string(),
                &host,
            ],
            timeout,
        )
        .await
    }

    /// nslookup：host + 可选 dns server（busybox 无 deadline，见 NSLOOKUP_TIMEOUT 注释）
    pub async fn nslookup(&self, host: &str, dns_server: Option<&str>) -> Result<ExecResult, UbusError> {
        let host = clean_host(host)?;
        let dns = match dns_server {
            Some(d) => Some(clean_host(d)?),
            None => None,
        };
        let args: Vec<&str> = match &dns {
            Some(d) => vec![&host, d],
            None => vec![&host],
        };
        self.exec("/usr/bin/nslookup", &args, NSLOOKUP_TIMEOUT).await
    }
}
