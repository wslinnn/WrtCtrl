//! wrtctrl-core：OpenWrt rpcd ubus 通信、会话管理与 UCI 写路径规则。
//!
//! 分层约束：所有路由器 IO 唯一入口在本 crate；
//! 安全写路径（rollback/confirm、session 预检、apply 白名单）只存在于这里。

pub mod apply;
pub mod candidates;
pub mod diag;
pub mod error;
pub mod files;
pub mod ping;
pub mod rpc;
pub mod session;
pub mod syslog;
pub mod tls_pin;
pub mod uci;
pub mod wireless;

pub use error::UbusError;
pub use rpc::{DeviceSession, RouterClient, EMPTY_SESSION};
pub use session::LoginOutcome;
pub use syslog::LogLevel;

/// crate 版本（JNI hello 冒烟会带回给 Kotlin 侧）
pub const VERSION: &str = env!("CARGO_PKG_VERSION");

/// 初始：验证 Kotlin → JNI → core 链路
pub fn hello() -> String {
    format!("wrtctrl-core v{VERSION} OK")
}

/// panic 演练：主动 panic，验证 JNI 边界的 catch_unwind 防线（进程必须存活）
pub fn panic_drill() {
    panic!("panic drill: this must be caught at the JNI boundary");
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hello_contains_version() {
        assert!(hello().starts_with("wrtctrl-core v"));
        assert!(hello().ends_with("OK"));
    }

    #[test]
    #[should_panic(expected = "panic drill")]
    fn panic_drill_panics() {
        panic_drill();
    }
}
