//! files.rs — 路由器文件读写（与 luci fs.read/write 同通道）。

use crate::error::UbusError;
use crate::rpc::RouterClient;
use crate::uci::UCI_CALL_TIMEOUT;
use serde_json::json;

impl RouterClient {
    /// 文件读（ubus file.read）→ 文本内容；无 data 字段返回空串
    pub async fn read_file(&self, path: &str) -> Result<String, UbusError> {
        let payload = self
            .call_ubus("file", "read", json!({"path": path}), UCI_CALL_TIMEOUT)
            .await?;
        Ok(payload
            .get("data")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string())
    }

    /// 文件写（ubus file.write）。data 原样写入，调用方负责规范化；mode 存在时才携带
    pub async fn write_file(&self, path: &str, data: &str, mode: Option<u32>) -> Result<(), UbusError> {
        let mut params = json!({"path": path, "data": data});
        if let Some(mode) = mode {
            params["mode"] = json!(mode);
        }
        self.call_ubus("file", "write", params, UCI_CALL_TIMEOUT)
            .await
            .map(|_| ())
    }
}
