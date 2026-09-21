//! candidates.rs — 编辑器下拉候选数据源。
//!
//! 设计决策：getConntrackHelpers 链路整体为死代码，不迁移；
//! 保留 5 类。失败降级语义说明：候选获取失败一律返回空列表，不阻塞页面。
//!
//! JSON 形状对齐旧 oa-uci-list candidates 契约（camelCase），Kotlin 侧零转换。

use crate::rpc::RouterClient;
use crate::uci::UCI_CALL_TIMEOUT;
use serde::Serialize;
use serde_json::{json, Value};
use std::time::Duration;

/// file.exec detectlp 超时
const DETECTLP_TIMEOUT: Duration = Duration::from_secs(8);

#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct Candidate {
    pub value: String,
    pub label: String,
}

#[derive(Debug, Clone, Serialize, Default, PartialEq)]
pub struct HostHints {
    #[serde(rename = "hosthintsMac")]
    pub hosthints_mac: Vec<Candidate>,
    #[serde(rename = "hosthintsIp")]
    pub hosthints_ip: Vec<Candidate>,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct UsbPrinterDetail {
    pub devname: String,
    pub product: String,
    pub model: String,
    pub description: String,
    /// VID:PID 形式（product 段补零到 4 位）；无斜杠时原样
    pub id: String,
    #[serde(rename = "devicePath")]
    pub device_path: String,
}

#[derive(Debug, Clone, Serialize, Default, PartialEq)]
pub struct UsbPrinters {
    pub printers: Vec<Candidate>,
    pub details: Vec<UsbPrinterDetail>,
    pub error: bool,
}

fn candidate(value: String, label: String) -> Candidate {
    Candidate { value, label }
}

/// 主机名限定标签：`值 (主机名)`；无主机名时裸值
fn hint_label(value: &str, name: &str) -> String {
    if name.is_empty() {
        value.to_string()
    } else {
        format!("{value} ({name})")
    }
}

impl RouterClient {
    /// luci-rpc getHostHints → MAC/IP 下拉候选。
    /// 响应形状：{hosts: {mac: {name, ipaddrs|ipv4}}}；无 hosts 包装时直接取载荷。
    pub async fn get_host_hint_candidates(&self) -> HostHints {
        let payload = match self
            .call_ubus("luci-rpc", "getHostHints", json!({}), UCI_CALL_TIMEOUT)
            .await
        {
            Ok(p) => p,
            Err(_) => return HostHints::default(),
        };
        let hosts = payload
            .get("hosts")
            .and_then(|v| v.as_object())
            .cloned()
            .unwrap_or_else(|| payload.as_object().cloned().unwrap_or_default());
        let mut hints = HostHints::default();
        for (mac, info) in &hosts {
            let name = info.get("name").and_then(|v| v.as_str()).unwrap_or("");
            hints
                .hosthints_mac
                .push(candidate(mac.clone(), hint_label(mac, name)));
            let addrs = info
                .get("ipaddrs")
                .or_else(|| info.get("ipv4"))
                .and_then(|v| v.as_array())
                .cloned()
                .unwrap_or_default();
            for addr in addrs {
                if let Some(ip) = addr.as_str() {
                    hints
                        .hosthints_ip
                        .push(candidate(ip.to_string(), hint_label(ip, name)));
                }
            }
        }
        hints
    }

    /// luci-rpc getNetworkDevices → 网络设备候选；过滤 lo 与 down 设备。
    /// 兜底：部分固件 luci-rpc 扩展缺失/返回空（同 getWirelessDevices
    /// 失败先例），回落从 network.interface dump 提取 device/l3_device 设备名。
    pub async fn get_device_candidates(&self) -> Vec<Candidate> {
        let mut devices = Vec::new();
        if let Ok(payload) = self
            .call_ubus("luci-rpc", "getNetworkDevices", json!({}), UCI_CALL_TIMEOUT)
            .await
        {
            if let Some(map) = payload.as_object() {
                for (name, info) in map {
                    if name == "lo" || info.get("up").and_then(|v| v.as_bool()) == Some(false) {
                        continue;
                    }
                    devices.push(candidate(name.clone(), name.clone()));
                }
            }
        }
        if devices.is_empty() {
            if let Ok(dump) = self
                .call_ubus("network.interface", "dump", json!({}), UCI_CALL_TIMEOUT)
                .await
            {
                if let Some(ifaces) = dump.get("interface").and_then(|v| v.as_array()) {
                    for iface in ifaces {
                        for key in ["device", "l3_device"] {
                            let dev = iface.get(key).and_then(|v| v.as_str());
                            if let Some(d) = dev {
                                if d != "lo" && !devices.iter().any(|c| c.value == d) {
                                    devices.push(candidate(d.to_string(), d.to_string()));
                                }
                            }
                        }
                    }
                }
            }
        }
        devices
    }

    /// uci network 中 .type=interface 的 section 名候选（samba4 interface 多选用）
    pub async fn get_interface_candidates(&self) -> Vec<Candidate> {
        let Ok(sections) = self.uci_get("network").await else {
            return Vec::new();
        };
        sections
            .values()
            .filter(|s| s.section_type == "interface")
            .map(|s| candidate(s.name.clone(), s.name.clone()))
            .collect()
    }

    /// firewall 中 .type=zone 的 name 候选（redirect/rule/nat/forwarding 的 src/dest 下拉）。
    /// 匿名 zone 无 name 选项时回退 section 名（对齐旧 `s.name || s['.name']`）
    pub async fn get_zone_candidates(&self) -> Vec<Candidate> {
        let Ok(sections) = self.uci_get("firewall").await else {
            return Vec::new();
        };
        sections
            .values()
            .filter(|s| s.section_type == "zone")
            .map(|s| {
                let name = s
                    .options
                    .get("name")
                    .and_then(|v| v.as_str())
                    .unwrap_or(&s.name);
                candidate(name.to_string(), name.to_string())
            })
            .collect()
    }

    /// conntrack helper 候选（rule/zone 的 helper 引用下拉）。
    /// 形状：payload.result[] 内 {name, description}：payload.result[] 内 {name, description}。
    pub async fn get_conntrack_helpers(&self) -> Vec<Candidate> {
        let Ok(payload) = self
            .call_ubus("luci", "getConntrackHelpers", json!({}), UCI_CALL_TIMEOUT)
            .await
        else {
            return Vec::new();
        };
        let Some(arr) = payload.get("result").and_then(|v| v.as_array()) else {
            return Vec::new();
        };
        arr.iter()
            .filter_map(|h| {
                let name = h.get("name")?.as_str()?;
                let desc = h.get("description").and_then(|v| v.as_str()).unwrap_or(name);
                Some(candidate(name.to_string(), format!("{desc} ({name})")))
            })
            .collect()
    }

    /// firewall 中 .type=ipset 的 name 候选（rule/redirect/nat 的「使用 ipset」下拉）
    pub async fn get_ipset_candidates(&self) -> Vec<Candidate> {
        let Ok(sections) = self.uci_get("firewall").await else {
            return Vec::new();
        };
        sections
            .values()
            .filter(|s| s.section_type == "ipset")
            .filter_map(|s| {
                s.options
                    .get("name")
                    .and_then(|v| v.as_str())
                    .map(|n| candidate(n.to_string(), n.to_string()))
            })
            .collect()
    }

    /// 接口 IPv4 候选（usb-printer bind：与 LuCI 一致（network_netlist——选接口、写该接口 IP）。
    /// value=IP（uci bind 落盘值），label=接口名 (IP)。
    pub async fn get_ifaddr_candidates(&self) -> Vec<Candidate> {
        let Ok(dump) = self
            .call_ubus("network.interface", "dump", json!({}), UCI_CALL_TIMEOUT)
            .await
        else {
            return Vec::new();
        };
        let Some(ifaces) = dump.get("interface").and_then(|v| v.as_array()) else {
            return Vec::new();
        };
        let mut out: Vec<Candidate> = Vec::new();
        for iface in ifaces {
            let name = iface.get("interface").and_then(|v| v.as_str()).unwrap_or("");
            let Some(addrs) = iface.get("ipv4-address").and_then(|v| v.as_array()) else {
                continue;
            };
            for addr in addrs {
                let Some(ip) = addr.get("address").and_then(|v| v.as_str()) else {
                    continue;
                };
                if out.iter().any(|c| c.value == ip) {
                    continue;
                }
                let label = if name.is_empty() {
                    ip.to_string()
                } else {
                    format!("{name} ({ip})")
                };
                out.push(candidate(ip.to_string(), label));
            }
        }
        out
    }

    /// iwinfo 实时枚举（信道/带宽/功率/国家码下拉来自实时 iwinfo）。
    /// kind ∈ freqlist|htmodes|txpowerlist|countrylist，device = radio 的 uci section 名
    /// （luci 同款用法——CBIWifiFrequencyValue 即以 section 名调 iwinfo）。
    /// 空 device / 失败 → 空列表（候选缺失回退静态枚举，不阻塞编辑页）。
    pub async fn get_iwinfo_candidates(&self, kind: &str, device: &str) -> Vec<Candidate> {
        if device.is_empty() {
            return Vec::new();
        }
        match self
            .call_ubus("iwinfo", kind, json!({"device": device}), UCI_CALL_TIMEOUT)
            .await
        {
            Ok(payload) => parse_iwinfo_candidates(kind, &payload),
            Err(_) => Vec::new(),
        }
    }

    /// USB 打印机发现：file.exec /usr/bin/detectlp（复用 luci 同款脚本）。
    /// stdout 每行 "devname,product,model,description..."；
    /// product=VID/PID/VER 内核串（即 uci device 的真实 value）。
    /// 探测失败 → error=true 的空结果（不阻塞页面）。
    pub async fn get_usb_printers(&self) -> UsbPrinters {
        let payload = match self
            .call_ubus(
                "file",
                "exec",
                json!({"command": "/usr/bin/detectlp"}),
                DETECTLP_TIMEOUT,
            )
            .await
        {
            Ok(p) => p,
            Err(_) => {
                return UsbPrinters {
                    error: true,
                    ..Default::default()
                }
            }
        };
        let stdout = payload.get("stdout").and_then(|v| v.as_str()).unwrap_or("");
        let mut printers = Vec::new();
        let mut details = Vec::new();
        for line in stdout.lines() {
            let line = line.trim();
            if line.is_empty() {
                continue;
            }
            let parts: Vec<&str> = line.split(',').collect();
            if parts.len() < 2 || parts[0].trim().is_empty() || parts[1].trim().is_empty() {
                continue;
            }
            let devname = parts[0].trim();
            let product = parts[1].trim();
            let model = parts.get(2).copied().unwrap_or("").trim();
            let description = parts[3..].join(",").trim().to_string();
            let segments: Vec<&str> = product.split('/').collect();
            let id = if segments.len() >= 2 {
                format!("{:0>4}:{:0>4}", segments[0], segments[1])
            } else {
                product.to_string()
            };
            printers.push(candidate(
                product.to_string(),
                format!(
                    "{} [{}]",
                    if !description.is_empty() {
                        description.as_str()
                    } else if !model.is_empty() {
                        model
                    } else {
                        devname
                    },
                    id
                ),
            ));
            details.push(UsbPrinterDetail {
                devname: devname.to_string(),
                product: product.to_string(),
                model: model.to_string(),
                description,
                id: id.clone(),
                device_path: format!("/dev/usb/{devname}"),
            });
        }
        UsbPrinters {
            printers,
            details,
            error: false,
        }
    }
}

/// 候选去重追加（freqlist 个别驱动可能重复上报信道）
fn push_unique(out: &mut Vec<Candidate>, value: String, label: String) {
    if !value.is_empty() && out.iter().all(|c| c.value != value) {
        out.push(candidate(value, label));
    }
}

/// iwinfo 响应纯解析（单测锚定）：results 数组按 kind 分形组装 value/label。
/// - freqlist：value=信道号，label=`号 (MHz)`；restricted 项跳过（luci 禁选同语义）；
///   no_ir（禁止射频发射）项同跳——选中会导致 AP 起不来
/// - htmodes：字符串数组（兼容对象键形态）
/// - txpowerlist：value=dBm 数值串（MTK 百分比语义由上层按 type 分支降级 TEXT，不走此列）
/// - countrylist：value=二字码，label=`国家 (码)`
pub(crate) fn parse_iwinfo_candidates(kind: &str, payload: &Value) -> Vec<Candidate> {
    let results = payload.get("results");
    let Some(results) = results else {
        return Vec::new();
    };
    let mut out: Vec<Candidate> = Vec::new();
    match kind {
        // 带宽：字符串数组；个别 iwinfo 版本 results 为映射（键即模式名），兼容取键
        "htmodes" => match results.as_array() {
            Some(arr) => arr.iter().for_each(|m| match m.as_str() {
                Some(s) => push_unique(&mut out, s.to_string(), s.to_string()),
                None => {
                    if let Some(obj) = m.as_object() {
                        for key in obj.keys() {
                            push_unique(&mut out, key.to_string(), key.to_string());
                        }
                    }
                }
            }),
            None => {
                if let Some(obj) = results.as_object() {
                    for key in obj.keys() {
                        push_unique(&mut out, key.to_string(), key.to_string());
                    }
                }
            }
        },
        // 其余 kinds 均要求 results 为数组
        _ => {
            let Some(arr) = results.as_array() else {
                return out;
            };
            match kind {
                "freqlist" => arr.iter().for_each(|f| {
                    let restricted = f.get("restricted").and_then(|v| v.as_bool()) == Some(true)
                        || f.get("no_ir").and_then(|v| v.as_bool()) == Some(true);
                    if restricted {
                        return;
                    }
                    let Some(ch) = f.get("channel").and_then(|v| v.as_i64()) else {
                        return;
                    };
                    if ch <= 0 {
                        return;
                    }
                    let mhz = f.get("mhz").and_then(|v| v.as_i64()).unwrap_or(0);
                    let label = if mhz > 0 {
                        format!("{ch} ({mhz} MHz)")
                    } else {
                        ch.to_string()
                    };
                    push_unique(&mut out, ch.to_string(), label);
                }),
                "txpowerlist" => arr.iter().for_each(|t| {
                    let Some(dbm) = t.get("dbm").and_then(|v| v.as_i64()) else {
                        return;
                    };
                    push_unique(&mut out, dbm.to_string(), format!("{dbm} dBm"));
                }),
                "countrylist" => arr.iter().for_each(|c| {
                    let Some(code) = c.get("code").and_then(|v| v.as_str()) else {
                        return;
                    };
                    if code.is_empty() {
                        return;
                    }
                    let name = c.get("country").and_then(|v| v.as_str()).unwrap_or("");
                    let label = if name.is_empty() {
                        code.to_string()
                    } else {
                        format!("{name} ({code})")
                    };
                    push_unique(&mut out, code.to_string(), label);
                }),
                _ => {}
            }
        }
    }
    out
}

/// serde 形状对齐守卫：JNI 边界 JSON 键必须与 Kotlin 侧 UciCandidates 解析契约一致
#[test]
fn host_hints_json_shape_is_camel_case() {
    let hints = HostHints {
        hosthints_mac: vec![Candidate {
            value: "a".into(),
            label: "b".into(),
        }],
        hosthints_ip: vec![],
    };
    let v = serde_json::to_value(&hints).unwrap();
    assert!(v.get("hosthintsMac").is_some());
    assert!(v.get("hosthintsIp").is_some());
}

#[test]
fn iwinfo_freqlist_skips_restricted_and_no_ir() {
    let payload = json!({"results": [
        {"channel": 1, "mhz": 2412, "restricted": false},
        {"channel": 12, "mhz": 2467, "restricted": true},
        {"channel": 0, "mhz": 2412},
        {"channel": 36, "mhz": 5180, "no_ir": true},
        {"channel": 6, "mhz": 2437, "restricted": false},
        {"channel": 6, "mhz": 2437, "restricted": false}
    ]});
    let out = parse_iwinfo_candidates("freqlist", &payload);
    assert_eq!(
        out,
        vec![
            Candidate {
                value: "1".into(),
                label: "1 (2412 MHz)".into()
            },
            Candidate {
                value: "6".into(),
                label: "6 (2437 MHz)".into()
            }
        ]
    );
}

#[test]
fn iwinfo_htmodes_and_txpower_and_country() {
    let modes = parse_iwinfo_candidates(
        "htmodes",
        &json!({"results": ["HT20", "HE40", "HE20", "HE40"]}),
    );
    assert_eq!(modes.len(), 3);
    assert_eq!(modes[0].value, "HT20");

    // 对象键形态兼容（个别 iwinfo 版本 results 为映射）
    let obj_modes = parse_iwinfo_candidates(
        "htmodes",
        &json!({"results": {"HT20": true, "HT40": false}}),
    );
    assert_eq!(obj_modes.len(), 2);

    let tx = parse_iwinfo_candidates(
        "txpowerlist",
        &json!({"results": [{"dbm": 20}, {"dbm": 16}, {"dbm": 20}]}),
    );
    assert_eq!(tx[0].value, "20");
    assert_eq!(tx[0].label, "20 dBm");
    assert_eq!(tx.len(), 2);

    let country = parse_iwinfo_candidates(
        "countrylist",
        &json!({"results": [{"code": "CN", "country": "China"}, {"code": "00"}]}),
    );
    assert_eq!(country[0].value, "CN");
    assert_eq!(country[0].label, "China (CN)");
    assert_eq!(country[1].label, "00");
}

#[test]
fn iwinfo_unknown_kind_and_missing_results_are_empty() {
    assert!(parse_iwinfo_candidates("nope", &json!({"results": [{"dbm": 1}]})).is_empty());
    assert!(parse_iwinfo_candidates("freqlist", &json!({})).is_empty());
}
