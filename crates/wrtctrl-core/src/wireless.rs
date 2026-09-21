//! wireless.rs — WiFi 无线操作。无线操作语义：
//!
//! 语义说明：
//! - radio 启停（对齐 luci network_updown）：device 与其下所有 wifi-iface 的 disabled
//!   同步（OR 关系——任一残留 disabled='1' 则 netifd 不建 BSS、SSID 不广播；只清 device
//!   不够，这是"没真正开启"的根因）+ uci.apply{rollback}(ucitrack→netifd up/down)
//!   + enable 时 /sbin/wifi up 兜底立即 up
//! - radio 重启：/sbin/wifi up <radio>（luci 官方 per-radio 重启路径；network.wireless
//!   up/down 无 ACL 授予）
//! - 无线提交直接走 uci_commit

use crate::error::UbusError;
use crate::rpc::RouterClient;
use crate::uci::{UciSection, UCI_CALL_TIMEOUT};
use serde::Serialize;
use serde_json::{json, Value};

#[derive(Debug, Clone, Serialize)]
pub struct RadioStatus {
    pub section: UciSection,
    pub iwinfo: Value,
    pub dev_interfaces: Vec<Value>,
}

#[derive(Debug, Clone, Serialize)]
pub struct IfaceStatus {
    pub section: UciSection,
    pub ifname: String,
    pub iwinfo: Value,
}

#[derive(Debug, Clone, Serialize)]
pub struct WirelessStatus {
    pub radios: Vec<RadioStatus>,
    pub ifaces: Vec<IfaceStatus>,
}

/// JS `Array.isArray(v) ? v : (v == null ? [] : [v])` 的等价物（uci list/scalar 兼容）
fn to_array(v: &Value) -> Vec<Value> {
    match v {
        Value::Array(a) => a.clone(),
        Value::Null => Vec::new(),
        other => vec![other.clone()],
    }
}

fn opt_str(section: &UciSection, key: &str) -> String {
    section
        .options
        .get(key)
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string()
}

impl RouterClient {
    /// 合并 uci wireless（radio/iface 字段）+ luci-rpc getWirelessDevices（iwinfo 概览）。
    /// 任一数据源失败降级为空（任一数据源失败降级为空），iface 关联 ifname：
    /// 从其 radio 的 devInterfaces 按 ifname/network 匹配（network 兼容 list 数组类型）
    pub async fn get_status(&self) -> WirelessStatus {
        let (uci_res, dev_res) = tokio::join!(
            self.uci_get("wireless"),
            self.call_ubus(
                "luci-rpc",
                "getWirelessDevices",
                json!({}),
                UCI_CALL_TIMEOUT
            )
        );
        let sections = uci_res.unwrap_or_default();
        let dev_data = dev_res.unwrap_or(Value::Null);
        let dev_of = |name: &str| dev_data.get(name).cloned().unwrap_or(Value::Null);

        let mut radios: Vec<RadioStatus> = Vec::new();
        let mut ifaces: Vec<IfaceStatus> = Vec::new();
        for (key, section) in &sections {
            match section.section_type.as_str() {
                "wifi-device" => {
                    let dev = dev_of(key);
                    radios.push(RadioStatus {
                        section: section.clone(),
                        iwinfo: dev.get("iwinfo").cloned().unwrap_or(Value::Null),
                        dev_interfaces: dev
                            .get("interfaces")
                            .and_then(|v| v.as_array())
                            .cloned()
                            .unwrap_or_default(),
                    });
                }
                "wifi-iface" => {
                    ifaces.push(IfaceStatus {
                        section: section.clone(),
                        ifname: opt_str(section, "ifname"),
                        iwinfo: Value::Null,
                    });
                }
                _ => {}
            }
        }

        // iface 关联 ifname：先按显式 ifname 匹配，再按 network 与 devInterface.config.network 交集
        for iface in &mut ifaces {
            let device = opt_str(&iface.section, "device");
            let radio = radios.iter().find(|r| r.section.name == device);
            let Some(radio) = radio else { continue };
            let iface_nets = to_array(iface.section.options.get("network").unwrap_or(&Value::Null));
            let explicit = opt_str(&iface.section, "ifname");
            let dev_iface = radio.dev_interfaces.iter().find(|d| {
                if !explicit.is_empty() && d.get("ifname").and_then(|v| v.as_str()) == Some(explicit.as_str())
                {
                    return true;
                }
                let dn = to_array(d.get("config").unwrap_or(&Value::Null).get("network").unwrap_or(&Value::Null));
                iface_nets.iter().any(|n| dn.contains(n))
            });
            if let Some(d) = dev_iface {
                if let Some(name) = d.get("ifname").and_then(|v| v.as_str()) {
                    iface.ifname = name.to_string();
                }
                iface.iwinfo = d.get("iwinfo").cloned().unwrap_or(Value::Null);
            }
        }

        WirelessStatus { radios, ifaces }
    }

    /// 关联终端（iwinfo assoclist，device=ifname）；空 ifname 或失败 → 空
    pub async fn get_assoc_list(&self, ifname: &str) -> Value {
        if ifname.is_empty() {
            return json!([]);
        }
        match self
            .call_ubus("iwinfo", "assoclist", json!({"device": ifname}), UCI_CALL_TIMEOUT)
            .await
        {
            // results 显式为 null 时同样回落空数组（unwrap_or 只兜键缺失，不兜 null 值）
            Ok(res) => res
                .get("results")
                .and_then(|v| v.as_array())
                .map(|a| json!(a))
                .unwrap_or(json!([])),
            Err(_) => json!([]),
        }
    }

    /// radio 启停：device 与其下所有 wifi-iface 的 disabled 同步（OR 关系，见模块注释）
    /// + commit{rollback}；enable 时 /sbin/wifi up 兜底立即 up。
    /// uci.get 失败时中止而非半写（否则只写 radio、
    /// 不知道 iface 列表——enable 场景恰好触发注释里"残留 disabled 导致没真正开启"的 bug）；
    /// radio 名先本地校验存在性（手里已有 sections），拼错名不再发往 rpcd
    pub async fn set_radio_enabled(&self, radio_name: &str, enabled: bool) -> Result<(), UbusError> {
        let sections = self.uci_get("wireless").await?;
        if !sections
            .values()
            .any(|s| s.section_type == "wifi-device" && s.name == radio_name)
        {
            return Err(UbusError::InvalidArgument(format!(
                "unknown radio: {radio_name}"
            )));
        }
        let val = if enabled { "0" } else { "1" };
        let mut iface_names: Vec<String> = sections
            .values()
            .filter(|s| {
                s.section_type == "wifi-iface"
                    && opt_str(s, "device") == radio_name
            })
            .map(|s| s.name.clone())
            .collect();
        iface_names.sort(); // 确定性（uci_get 为 BTreeMap 序，此处保持显式）
        self.uci_set("wireless", radio_name, json!({"disabled": val}))
            .await?;
        // set 间无依赖
        for name in &iface_names {
            self.uci_set("wireless", name, json!({"disabled": val})).await?;
        }
        self.uci_commit("wireless").await?;
        if enabled {
            // wifi up 失败不再零痕迹：stderr/stderr 链进 stderr（Android 侧 best-effort
            // 进 logcat），实测「开了但没起来」至少有排查线索
            if let Err(e) = self
                .call_ubus(
                    "file",
                    "exec",
                    json!({"command": "/sbin/wifi", "params": ["up", radio_name]}),
                    UCI_CALL_TIMEOUT,
                )
                .await
            {
                eprintln!("wrtctrl: wifi up {radio_name} failed: {e}");
            }
        }
        Ok(())
    }

    /// radio 重启：/sbin/wifi up <radio>（luci 官方 per-radio 重启路径）
    pub async fn restart_radio(&self, radio_name: &str) -> Result<Value, UbusError> {
        self.call_ubus(
            "file",
            "exec",
            json!({"command": "/sbin/wifi", "params": ["up", radio_name]}),
            UCI_CALL_TIMEOUT,
        )
        .await
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn to_array_semantics() {
        assert_eq!(to_array(&json!(["a"])), vec![json!("a")]);
        assert_eq!(to_array(&json!("a")), vec![json!("a")]);
        assert_eq!(to_array(&Value::Null), Vec::<Value>::new());
    }
}
