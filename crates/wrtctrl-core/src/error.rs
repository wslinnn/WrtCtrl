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
