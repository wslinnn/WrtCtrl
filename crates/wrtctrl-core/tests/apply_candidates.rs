//! apply 白名单 + 候选降级语义快照测试。

mod common;

use common::{client_to, last_call_params, ok_response, ubus_err_response};
use serde_json::json;
use wiremock::{Mock, MockServer};
use wrtctrl_core::error::UbusError;

// ── apply 白名单 ──

/// 契约：合法调用 → file.exec {command:/etc/init.d/<script>, params:[action]}
#[tokio::test]
async fn apply_calls_file_exec() {
    let server = MockServer::start().await;
    Mock::given(common::UbusCall("file", "exec"))
        .respond_with(ok_response(json!({"code": 0})))
        .mount(&server)
        .await;
    let client = client_to(&server, "s").await;

    client.apply("firewall", "reload").await.unwrap();

    let params = last_call_params(&server, "file", "exec").await;
    assert_eq!(
        params[3],
        json!({"command": "/etc/init.d/firewall", "params": ["reload"]})
    );
}

/// 契约：注入尝试被本地白名单拒绝，且不发出任何网络请求
#[tokio::test]
async fn apply_rejects_injection_locally() {
    let server = MockServer::start().await;
    let client = client_to(&server, "s").await;

    for script in ["firewall; reboot", "../init.d", "a b", "防火墙"] {
        let err = client.apply(script, "reload").await.unwrap_err();
        assert!(matches!(err, UbusError::InvalidArgument(_)), "{script}: {err:?}");
    }
    let err = client.apply("firewall", "reboot").await.unwrap_err();
    assert!(matches!(err, UbusError::InvalidArgument(_)));

    assert!(
        server.received_requests().await.unwrap_or_default().is_empty(),
        "本地拒绝不得发出网络请求"
    );
}

/// 契约：空 initScript 为 no-op（旧 `if (!initScript) return resolve()`）
#[tokio::test]
async fn apply_empty_script_is_noop() {
    let server = MockServer::start().await;
    let client = client_to(&server, "s").await;

    client.apply("", "reload").await.unwrap();

    assert!(server.received_requests().await.unwrap_or_default().is_empty());
}

// ── host hints ──

/// 契约：解析 hosts 映射；有主机名的候选标签为 `值 (主机名)`；ipaddrs 缺失时回退 ipv4
#[tokio::test]
async fn host_hints_parse_named_and_unnamed() {
    let server = MockServer::start().await;
    Mock::given(common::UbusCall("luci-rpc", "getHostHints"))
        .respond_with(ok_response(json!({
            "hosts": {
                "AA:BB:CC:00:00:01": {"name": "手机", "ipaddrs": ["192.168.1.50"]},
                "AA:BB:CC:00:00:02": {"ipv4": ["10.0.0.2"]}
            }
        })))
        .mount(&server)
        .await;
    let client = client_to(&server, "s").await;

    let hints = client.get_host_hint_candidates().await;

    assert_eq!(hints.hosthints_mac.len(), 2);
    assert!(hints.hosthints_mac.iter().any(|c| c.label == "AA:BB:CC:00:00:01 (手机)"));
    assert!(hints.hosthints_mac.iter().any(|c| c.label == "AA:BB:CC:00:00:02"));
    assert_eq!(hints.hosthints_ip.len(), 2);
    assert!(hints.hosthints_ip.iter().any(|c| c.value == "192.168.1.50" && c.label == "192.168.1.50 (手机)"));
    assert!(hints.hosthints_ip.iter().any(|c| c.value == "10.0.0.2" && c.label == "10.0.0.2"));
}

/// 契约：无 hosts 包装时直接从载荷解析（旧 `hints.hosts || hints` 分支）
#[tokio::test]
async fn host_hints_fallback_to_flat_payload() {
    let server = MockServer::start().await;
    Mock::given(common::UbusCall("luci-rpc", "getHostHints"))
        .respond_with(ok_response(json!({
            "AA:BB:CC:00:00:09": {"name": "nas", "ipaddrs": ["192.168.1.9"]}
        })))
        .mount(&server)
        .await;
    let client = client_to(&server, "s").await;

    let hints = client.get_host_hint_candidates().await;
    assert_eq!(hints.hosthints_mac.len(), 1);
    assert_eq!(hints.hosthints_ip[0].value, "192.168.1.9");
}

/// 契约：候选获取失败一律降级为空列表，不阻塞页面（失败降级语义）
#[tokio::test]
async fn host_hints_degrade_to_empty_on_error() {
    let server = MockServer::start().await;
    Mock::given(common::UbusCall("luci-rpc", "getHostHints"))
        .respond_with(ubus_err_response(6))
        .mount(&server)
        .await;
    let client = client_to(&server, "s").await;

    let hints = client.get_host_hint_candidates().await;
    assert!(hints.hosthints_mac.is_empty());
    assert!(hints.hosthints_ip.is_empty());
}

// ── 网络设备 / 接口 / zone 候选 ──

/// 契约：过滤 lo 与 down 设备
#[tokio::test]
async fn device_candidates_filter_lo_and_down() {
    let server = MockServer::start().await;
    Mock::given(common::UbusCall("luci-rpc", "getNetworkDevices"))
        .respond_with(ok_response(json!({
            "lan": {"up": true},
            "lo": {"up": true},
            "wlan0": {"up": false},
            "eth0": {}
        })))
        .mount(&server)
        .await;
    let client = client_to(&server, "s").await;

    let devices = client.get_device_candidates().await;
    let mut values: Vec<&str> = devices.iter().map(|d| d.value.as_str()).collect();
    values.sort_unstable();
    // 注：serde_json Map 按 key 排序，候选顺序为字母序（对 UI 无影响，确定性更强）
    assert_eq!(values, ["eth0", "lan"]);
}

/// 契约：接口候选 = network config 中 .type=interface 的 section 名
#[tokio::test]
async fn interface_candidates_from_network_config() {
    let server = MockServer::start().await;
    Mock::given(common::UbusCall("uci", "get"))
        .respond_with(ok_response(json!({
            "values": {
                "lan": {".type": "interface", ".anonymous": false, ".name": "lan"},
                "wan6": {".type": "interface", ".anonymous": false, ".name": "wan6"},
                "default": {".type": "system", ".anonymous": false, ".name": "default"}
            }
        })))
        .mount(&server)
        .await;
    let client = client_to(&server, "s").await;

    let interfaces = client.get_interface_candidates().await;
    let values: Vec<&str> = interfaces.iter().map(|d| d.value.as_str()).collect();
    assert_eq!(values, ["lan", "wan6"]);
}

/// 契约：zone 候选 = firewall config 中 .type=zone 的 name 值；
/// 匿名 zone（无 name 选项）回退 section 名（旧 `s.name || s['.name']`）
#[tokio::test]
async fn zone_candidates_from_firewall_config() {
    let server = MockServer::start().await;
    Mock::given(common::UbusCall("uci", "get"))
        .respond_with(ok_response(json!({
            "values": {
                "z1": {".type": "zone", ".anonymous": true, ".name": "z1", "name": "lan"},
                "z2": {".type": "zone", ".anonymous": true, ".name": "z2", "name": "wan"},
                "z3": {".type": "zone", ".anonymous": true, ".name": "z3"},
                "r1": {".type": "rule", ".anonymous": true, ".name": "r1"}
            }
        })))
        .mount(&server)
        .await;
    let client = client_to(&server, "s").await;

    let zones = client.get_zone_candidates().await;
    let values: Vec<&str> = zones.iter().map(|d| d.value.as_str()).collect();
    assert_eq!(values, ["lan", "wan", "z3"]);
}

// ── USB 打印机 ──

/// 契约：detectlp stdout 逐行解析 "devname,product,model,description..."；
/// product 段补零成 VID:PID；标签 = 描述(或模型/设备名回退) + [VID:PID]
#[tokio::test]
async fn usb_printers_parse_detectlp_output() {
    let server = MockServer::start().await;
    Mock::given(common::UbusCall("file", "exec"))
        .respond_with(ok_response(json!({
            "code": 0,
            "stdout": "001,04f9/02a0/0100,HL-2240,Brother Laser Printer\n002,abc,Linux USB Printer\n"
        })))
        .mount(&server)
        .await;
    let client = client_to(&server, "s").await;

    let result = client.get_usb_printers().await;

    assert!(!result.error);
    assert_eq!(result.printers.len(), 2);
    assert_eq!(result.printers[0].value, "04f9/02a0/0100");
    assert_eq!(result.printers[0].label, "Brother Laser Printer [04f9:02a0]");
    assert_eq!(result.printers[1].label, "Linux USB Printer [abc]");
    assert_eq!(result.details[0].id, "04f9:02a0");
    assert_eq!(result.details[0].device_path, "/dev/usb/001");
    assert_eq!(result.details[0].model, "HL-2240");
    assert_eq!(result.details[0].description, "Brother Laser Printer");
}

/// 契约：探测失败 → error=true 的空结果（失败分支）
#[tokio::test]
async fn usb_printers_error_flag_on_failure() {
    let server = MockServer::start().await;
    Mock::given(common::UbusCall("file", "exec"))
        .respond_with(ubus_err_response(12))
        .mount(&server)
        .await;
    let client = client_to(&server, "s").await;

    let result = client.get_usb_printers().await;
    assert!(result.error);
    assert!(result.printers.is_empty());
    assert!(result.details.is_empty());
}
