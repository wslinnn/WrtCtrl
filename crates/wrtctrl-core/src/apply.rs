//! apply.rs — init.d 服务操作。
//!
//! 语义说明：
//! - 走 file.exec 通道调 /etc/init.d/<script> <action>
//! - initScript/action 白名单正则防注入（^锚定全匹配 → Rust 侧等价于集合校验 + 非空）
//! - 空 initScript 为 no-op（旧 `if (!initScript) return Promise.resolve()`）
//! - 超时 12s（超时 12s）

use crate::error::UbusError;
use crate::rpc::RouterClient;
use serde_json::json;
use std::time::Duration;

const APPLY_TIMEOUT: Duration = Duration::from_secs(12);

const ACTIONS: [&str; 4] = ["reload", "restart", "start", "stop"];

/// 白名单等价校验：非空且全部字符 ∈ [a-zA-Z0-9_-]
fn is_safe_script_name(name: &str) -> bool {
    !name.is_empty()
        && name
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
}

impl RouterClient {
    /// 调 /etc/init.d/<init_script> <action>（file exec 通道）
    pub async fn apply(&self, init_script: &str, action: &str) -> Result<(), UbusError> {
        if init_script.is_empty() {
            return Ok(());
        }
        if !is_safe_script_name(init_script) {
            return Err(UbusError::InvalidArgument(format!(
                "invalid init script name: {init_script}"
            )));
        }
        if !ACTIONS.contains(&action) {
            return Err(UbusError::InvalidArgument(format!(
                "invalid action: {action}"
            )));
        }
        self.call_ubus(
            "file",
            "exec",
            json!({
                "command": format!("/etc/init.d/{init_script}"),
                "params": [action],
            }),
            APPLY_TIMEOUT,
        )
        .await
        .map(|_| ())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn safe_script_name_accepts_normal() {
        assert!(is_safe_script_name("firewall"));
        assert!(is_safe_script_name("wolultra-daemon_2"));
        assert!(!is_safe_script_name(""));
        assert!(!is_safe_script_name("firewall; reboot"));
        assert!(!is_safe_script_name("../etc"));
        assert!(!is_safe_script_name("a b"));
        assert!(!is_safe_script_name("防火墙"));
    }

    #[test]
    fn action_whitelist_exact_match() {
        assert!(ACTIONS.contains(&"reload"));
        assert!(!ACTIONS.contains(&"reboot"));
    }
}
