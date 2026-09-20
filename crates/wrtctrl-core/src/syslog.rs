//! syslog.rs — 系统日志/内核日志读取。日志语义：
//!
//! 语义说明：
//! - file.exec /usr/libexec/syslog-wrapper（ACL luci-mod-status 授予）。ubus log read
//!   在目标固件返回空（logd ubus 接口读不到 ring buffer），故走 wrapper；10s 硬超时兜底
//! - dmesg 走 /bin/dmesg -r（ACL 授予 "/bin/dmesg -r"）
//! - 不在 app 端截断：
//!   日志淘汰由路由器 logd 环形缓冲区自身负责，wrapper 返回什么就展示什么
//! - dmesg 走 /bin/dmesg -r（ACL 授予 "/bin/dmesg -r"）

use crate::error::UbusError;
use crate::rpc::RouterClient;
use serde::Serialize;
use serde_json::json;
use std::time::Duration;

const SYSLOG_TIMEOUT: Duration = Duration::from_secs(10);

#[derive(Debug, Clone, Copy, Serialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum LogLevel {
    Err,
    Warn,
    Info,
}

#[derive(Debug, Clone, Serialize)]
pub struct LogLine {
    pub text: String,
    pub level: LogLevel,
}

const SEV_TOKENS: [(&str, LogLevel); 9] = [
    ("emerg", LogLevel::Err),
    ("alert", LogLevel::Err),
    ("crit", LogLevel::Err),
    ("err", LogLevel::Err),
    ("warning", LogLevel::Warn),
    ("warn", LogLevel::Warn),
    ("notice", LogLevel::Info),
    ("info", LogLevel::Info),
    ("debug", LogLevel::Info),
];

/// syslog(logread) 文本行 "Mon DD HH:MM:SS facility.severity msg" → 取 .severity 分类。
/// 分级正则 `.(emerg|...)\b`：最左出现位置优先，同位置按枚举顺序；
/// \b 边界 = severity 后不得跟字母/数字/下划线
pub fn classify_syslog(line: &str) -> LogLevel {
    let mut best: Option<(usize, LogLevel)> = None;
    for (token, level) in SEV_TOKENS {
        let needle = format!(".{token}");
        let mut from = 0usize;
        while let Some(rel) = line[from..].find(&needle) {
            let abs = from + rel;
            let after = abs + needle.len();
            let boundary_ok = line[after..]
                .chars()
                .next()
                .map_or(true, |c| !(c.is_ascii_alphanumeric() || c == '_'));
            if boundary_ok {
                if best.map_or(true, |(pos, _)| abs < pos) {
                    best = Some((abs, level));
                }
                break;
            }
            from = abs + 1;
            if from >= line.len() {
                break;
            }
        }
    }
    best.map(|(_, level)| level).unwrap_or(LogLevel::Info)
}

/// dmesg -r 行 "<N>[uptime] msg" → 取 <N> 数字分类（ubox 偶发 14-15，宽松匹配）。
/// N<=3=err(emerg/alert/crit/err)、N=4=warn、其余=info；不匹配行为 info
pub fn classify_dmesg(line: &str) -> LogLevel {
    let Some(rest) = line.strip_prefix('<') else {
        return LogLevel::Info;
    };
    let Some(gt) = rest.find('>') else {
        return LogLevel::Info;
    };
    let Ok(n) = rest[..gt].parse::<u32>() else {
        return LogLevel::Info;
    };
    if n <= 3 {
        LogLevel::Err
    } else if n == 4 {
        LogLevel::Warn
    } else {
        LogLevel::Info
    }
}

/// 全量行化：空行剔除、逐行分级，不做条数截断（淘汰责任在路由器 logd 环形缓冲区）
fn to_lines(stdout: &str, classify: fn(&str) -> LogLevel) -> Vec<LogLine> {
    stdout
        .lines()
        .filter(|l| !l.is_empty())
        .map(|l| LogLine {
            text: l.to_string(),
            level: classify(l),
        })
        .collect()
}

impl RouterClient {
    /// 系统日志：/usr/libexec/syslog-wrapper
    pub async fn read_syslog(&self) -> Result<Vec<LogLine>, UbusError> {
        let payload = self
            .call_ubus(
                "file",
                "exec",
                json!({"command": "/usr/libexec/syslog-wrapper", "params": [], "env": null}),
                SYSLOG_TIMEOUT,
            )
            .await?;
        let stdout = payload.get("stdout").and_then(|v| v.as_str()).unwrap_or("");
        Ok(to_lines(stdout, classify_syslog))
    }

    /// 内核日志：/bin/dmesg -r
    pub async fn read_dmesg(&self) -> Result<Vec<LogLine>, UbusError> {
        let payload = self
            .call_ubus(
                "file",
                "exec",
                json!({"command": "/bin/dmesg", "params": ["-r"], "env": null}),
                SYSLOG_TIMEOUT,
            )
            .await?;
        let stdout = payload.get("stdout").and_then(|v| v.as_str()).unwrap_or("");
        Ok(to_lines(stdout, classify_dmesg))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn syslog_classification() {
        assert_eq!(
            classify_syslog("Mon Sep 17 12:00:00 2024 kern.err kernel: oops"),
            LogLevel::Err
        );
        assert_eq!(
            classify_syslog("Mon Sep 17 12:00:00 2024 daemon.warn dnsmasq[1]: slow"),
            LogLevel::Warn
        );
        assert_eq!(
            classify_syslog("Mon Sep 17 12:00:00 2024 user.notice root: hi"),
            LogLevel::Info
        );
        assert_eq!(classify_syslog("no severity here"), LogLevel::Info);
        // 词边界：.info 后跟字母不是 severity（.information）
        assert_eq!(classify_syslog("facility.informational msg"), LogLevel::Info);
    }

    #[test]
    fn dmesg_classification() {
        assert_eq!(classify_dmesg("<3>[ 1.000] ext4 error"), LogLevel::Err);
        assert_eq!(classify_dmesg("<4>[ 2.000] warning"), LogLevel::Warn);
        assert_eq!(classify_dmesg("<6>[ 3.000] nic ok"), LogLevel::Info);
        assert_eq!(classify_dmesg("<15>[ 4.000] ubox odd"), LogLevel::Info);
        assert_eq!(classify_dmesg("no prefix"), LogLevel::Info);
    }

    #[test]
    fn lines_preserve_all() {
        let stdout: String = (0..805).map(|i| format!("line{i}\n")).collect();
        let lines = to_lines(&stdout, |_| LogLevel::Info);
        assert_eq!(lines.len(), 805);
        assert_eq!(lines.first().unwrap().text, "line0");
        assert_eq!(lines.last().unwrap().text, "line804");
    }
}
