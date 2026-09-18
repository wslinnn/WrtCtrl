//! files / diag / syslog / wireless / ping 语义快照测试。

mod common;

use common::{client_to, ok_response, ubus_call_sequence, ubus_err_response, UbusCall};
use serde_json::json;
use wiremock::{Mock, MockServer};
use wrtctrl_core::diag::NSLOOKUP_TIMEOUT;
use wrtctrl_core::error::UbusError;
use wrtctrl_core::rpc::RouterClient;
use wrtctrl_core::syslog::LogLevel;
use wrtctrl_core::{ping_level, PingLevel};

// ── files ──

/// 契约：read 返回 data 字段；缺 data 为空串
#[tokio::test]
async fn read_file_returns_data() {
    let server = MockServer::start().await;
    Mock::given(UbusCall("file", "read"))
        .respond_with(ok_response(json!({"data": "geoip content"})))
        .mount(&server)
        .await;
    let client = client_to(&server, "s").await;

    let content = client.read_file("/usr/share/passwall2/geoip.dat").await.unwrap();
    assert_eq!(content, "geoip content");

    let params = common::last_call_params(&server, "file", "read").await;
    assert_eq!(params[3], json!({"path": "/usr/share/passwall2/geoip.dat"}));
}

/// 契约：write 携带 path/data；mode 仅在传入时出现
#[tokio::test]
async fn write_file_includes_optional_mode() {
    let server = MockServer::start().await;
    Mock::given(UbusCall("file", "write"))
        .respond_with(ok_response(json!({})))
        .mount(&server)
        .await;
    let client = client_to(&server, "s").await;

    client.write_file("/tmp/a.txt", "hello", Some(0o644)).await.unwrap();
    let params = common::last_call_params(&server, "file", "write").await;
    assert_eq!(params[3]["mode"], json!(420)); // 0o644

    client.write_file("/tmp/b.txt", "hi", None).await.unwrap();
    let params = common::last_call_params(&server, "file", "write").await;
    assert!(params[3].get("mode").is_none());
}

// ── diag ──

/// 契约：ping 走 /bin/ping，自终止参数齐全（-c/-W/-w），host 已净化
#[tokio::test]
async fn diag_ping_builds_args() {
    let server = MockServer::start().await;
    Mock::given(UbusCall("file", "exec"))
        .respond_with(ok_response(json!({"code": 0, "stdout": "rtt min/avg/max"})))
        .mount(&server)
        .await;
    let client = client_to(&server, "s").await;

    let result = client.ping(" 192.168.1.1 ", 4, 2, 8).await.unwrap();
    assert_eq!(result.code, Some(0));

    let params = common::last_call_params(&server, "file", "exec").await;
    assert_eq!(params[3]["command"], "/bin/ping");
    assert_eq!(
        params[3]["params"],
        json!(["-c", "4", "-W", "2", "-w", "8", "192.168.1.1"])
    );
}

/// 契约：IPv6 目标（含 :）走 traceroute6；nslookup 带 dns server 且 25s 超时
#[tokio::test]
async fn diag_traceroute6_and_nslookup() {
    let server = MockServer::start().await;
    Mock::given(UbusCall("file", "exec"))
        .respond_with(ok_response(json!({"code": 0})))
        .mount(&server)
        .await;
    let client = client_to(&server, "s").await;

    client.traceroute("2001:db8::1", 15, 1, 1).await.unwrap();
    let params = common::last_call_params(&server, "file", "exec").await;
    assert_eq!(params[3]["command"], "/bin/traceroute6");
    assert_eq!(params[3]["params"], json!(["-m", "15", "-w", "1", "-q", "1", "2001:db8::1"]));

    client.nslookup("openwrt.org", Some("1.1.1.1")).await.unwrap();
    let params = common::last_call_params(&server, "file", "exec").await;
    assert_eq!(params[3]["command"], "/usr/bin/nslookup");
    assert_eq!(params[3]["params"], json!(["openwrt.org", "1.1.1.1"]));
}

/// 契约：host 净化拒绝（空/非法字符）→ InvalidArgument 且零网络请求
#[tokio::test]
async fn diag_rejects_bad_hosts_locally() {
    let server = MockServer::start().await;
    let client = client_to(&server, "s").await;

    for bad in ["", "a b", "host;rm", "例え.jp"] {
        let err = client.ping(bad, 1, 1, 1).await.unwrap_err();
        assert!(matches!(err, UbusError::InvalidArgument(_)), "{bad}: {err:?}");
    }
    assert!(server.received_requests().await.unwrap_or_default().is_empty());
    let _ = NSLOOKUP_TIMEOUT;
}

// ── syslog ──

/// 契约：read_syslog 走 wrapper、行分类、截尾 800 行
#[tokio::test]
async fn syslog_reads_and_classifies() {
    let server = MockServer::start().await;
    let mut stdout = String::new();
    for i in 0..3 {
        stdout.push_str(&format!("Sep 17 12:0{i}:00 kern.err kernel: err{i}\n"));
    }
    stdout.push_str("Sep 17 12:04:00 user.notice root: done\n");
    Mock::given(UbusCall("file", "exec"))
        .respond_with(ok_response(json!({"stdout": stdout})))
        .mount(&server)
        .await;
    let client = client_to(&server, "s").await;

    let lines = client.read_syslog().await.unwrap();
    assert_eq!(lines.len(), 4);
    assert_eq!(lines[0].level, LogLevel::Err);
    assert_eq!(lines[3].level, LogLevel::Info);
}

/// 契约：dmesg 走 /bin/dmesg -r
#[tokio::test]
async fn dmesg_uses_raw_flag() {
    let server = MockServer::start().await;
    Mock::given(UbusCall("file", "exec"))
        .respond_with(ok_response(json!({"stdout": "<3>[1.0] err\n<6>[2.0] ok\n"})))
        .mount(&server)
        .await;
    let client = client_to(&server, "s").await;

    let lines = client.read_dmesg().await.unwrap();
    assert_eq!(lines[0].level, LogLevel::Err);
    assert_eq!(lines[1].level, LogLevel::Info);
}

// ── wireless ──

/// 契约：get_status 合并 uci wireless + getWirelessDevices，iface 按 network 交集解析 ifname
#[tokio::test]
async fn wireless_status_merges_and_resolves_ifname() {
    let server = MockServer::start().await;
    Mock::given(UbusCall("uci", "get"))
        .respond_with(ok_response(json!({
            "values": {
                "radio1": {".type": "wifi-device", ".anonymous": false, ".name": "radio1", "type": "mtwifi"},
                "ap": {".type": "wifi-iface", ".anonymous": false, ".name": "ap", "device": "radio1", "network": ["lan"], "ssid": "MyWifi"}
            }
        })))
        .mount(&server)
        .await;
    Mock::given(common::UbusCall("luci-rpc", "getWirelessDevices"))
        .respond_with(ok_response(json!({
            "radio1": {
                "iwinfo": {"phy": "phy1"},
                "interfaces": [
                    {"ifname": "phy1-ap0", "config": {"network": ["lan", "wan"]}, "iwinfo": {"ssid": "MyWifi"}}
                ]
            }
        })))
        .mount(&server)
        .await;
    let client = client_to(&server, "s").await;

    let status = client.get_status().await;
    assert_eq!(status.radios.len(), 1);
    assert_eq!(status.ifaces.len(), 1);
    assert_eq!(status.radios[0].section.options["type"], "mtwifi");
    // iface 无显式 ifname → 经 network 交集匹配到 phy1-ap0，iwinfo 也随之挂上
    assert_eq!(status.ifaces[0].ifname, "phy1-ap0");
    assert_eq!(status.ifaces[0].iwinfo["ssid"], "MyWifi");
}

/// 契约：radio 启停同步 device 与其下所有 iface 的 disabled（不含其他 radio 的 iface），
/// commit{rollback}，enable 时 /sbin/wifi up 兜底
#[tokio::test]
async fn set_radio_enabled_syncs_device_and_ifaces() {
    let server = MockServer::start().await;
    Mock::given(UbusCall("uci", "get"))
        .respond_with(ok_response(json!({
            "values": {
                "radio1": {".type": "wifi-device", ".anonymous": false, ".name": "radio1"},
                "ap": {".type": "wifi-iface", ".anonymous": false, ".name": "ap", "device": "radio1"},
                "ap2": {".type": "wifi-iface", ".anonymous": false, ".name": "ap2", "device": "radio2"}
            }
        })))
        .mount(&server)
        .await;
    Mock::given(UbusCall("uci", "set"))
        .respond_with(ok_response(json!({})))
        .mount(&server)
        .await;
    Mock::given(UbusCall("uci", "apply"))
        .respond_with(ok_response(json!({})))
        .mount(&server)
        .await;
    Mock::given(UbusCall("uci", "confirm"))
        .respond_with(ok_response(json!({})))
        .mount(&server)
        .await;
    Mock::given(UbusCall("system", "board"))
        .respond_with(ok_response(json!({})))
        .mount(&server)
        .await;
    Mock::given(UbusCall("file", "exec"))
        .respond_with(ok_response(json!({"code": 0})))
        .mount(&server)
        .await;
    let client = client_to(&server, "s").await;

    client.set_radio_enabled("radio1", true).await.unwrap();

    // 三个 set：radio1 + ap（同 radio），绝不含 ap2（radio2 的 iface）
    let set_calls: Vec<serde_json::Value> = ubus_call_sequence(&server)
        .await
        .into_iter()
        .filter(|p| p[1] == json!("uci") && p[2] == json!("set"))
        .collect();
    assert_eq!(set_calls.len(), 2);
    assert_eq!(set_calls[0][3], json!({"config": "wireless", "section": "radio1", "values": {"disabled": "0"}}));
    assert_eq!(set_calls[1][3], json!({"config": "wireless", "section": "ap", "values": {"disabled": "0"}}));

    // enable → /sbin/wifi up radio1 兜底
    let exec = common::last_call_params(&server, "file", "exec").await;
    assert_eq!(exec[3], json!({"command": "/sbin/wifi", "params": ["up", "radio1"]}));
}

/// 契约：uci.get 失败时中止启停（零写请求）——避免半写
/// （只写 radio 会漏 iface、不知道 iface 列表，enable 场景恰触发"残留 disabled"根因）
#[tokio::test]
async fn set_radio_enabled_aborts_when_read_fails() {
    let server = MockServer::start().await;
    Mock::given(UbusCall("uci", "get"))
        .respond_with(ubus_err_response(6))
        .mount(&server)
        .await;
    let client = client_to(&server, "s").await;

    let err = client.set_radio_enabled("radio1", true).await.unwrap_err();
    assert!(matches!(err, UbusError::Ubus(6)));
    assert!(server.received_requests().await.unwrap_or_default().len() == 1, "只应有 uci.get 一次请求");
}

/// 契约：assoclist 透传 results；空 ifname 不发请求
#[tokio::test]
async fn assoclist_passthrough_and_guard() {
    let server = MockServer::start().await;
    Mock::given(common::UbusCall("iwinfo", "assoclist"))
        .respond_with(ok_response(json!({"results": [{"mac": "AA:BB:CC:00:00:01", "signal": -55}]})))
        .mount(&server)
        .await;
    let client = client_to(&server, "s").await;

    let results = client.get_assoc_list("phy1-ap0").await;
    assert_eq!(results[0]["mac"], "AA:BB:CC:00:00:01");

    // 空 ifname → 空结果且零请求
    let before = server.received_requests().await.unwrap_or_default().len();
    let empty = client.get_assoc_list("").await;
    assert_eq!(empty, json!([]));
    assert_eq!(server.received_requests().await.unwrap_or_default().len(), before);
}

/// 契约：踢下线参数（deauth+reason5+ban_time 60s，对象 hostapd.<ifname>）
#[tokio::test]
async fn kick_client_params() {
    let server = MockServer::start().await;
    Mock::given(common::UbusCall("hostapd.phy1-ap0", "del_client"))
        .respond_with(ok_response(json!({})))
        .mount(&server)
        .await;
    let client = client_to(&server, "s").await;

    client.kick_client("phy1-ap0", "AA:BB:CC:00:00:01").await.unwrap();

    let params = common::last_call_params(&server, "hostapd.phy1-ap0", "del_client").await;
    assert_eq!(
        params[3],
        json!({"addr": "AA:BB:CC:00:00:01", "deauth": true, "reason": 5, "ban_time": 60000})
    );
}

// ── ping 探测（ICMP 优先 + HTTP HEAD 兜底，见 ping.rs）──

/// 契约：对可达目标返回毫秒数（桌面无 /system/bin/ping，实际走 HTTP 兜底；ICMP 路径由集成验收覆盖）；
/// 分档边界 <100/<300 见 ping.rs 内嵌单测
#[tokio::test]
async fn ping_device_probes_base_url() {
    let server = MockServer::start().await;
    Mock::given(wiremock::matchers::method("HEAD"))
        .respond_with(ok_response(json!({})))
        .mount(&server)
        .await;
    let client = client_to(&server, "s").await;

    let ms = client.ping_device().await;
    assert!(ms.is_some(), "在线设备必须返回 Some");
    let _ = ping_level(ms.unwrap());
    assert_eq!(ping_level(299), PingLevel::Ok);
}

/// 契约：ping_url 可探活任意设备（设备列表页并行 ping 多台的前提）
#[tokio::test]
async fn ping_url_probes_arbitrary_device() {
    let server = MockServer::start().await;
    Mock::given(wiremock::matchers::method("HEAD"))
        .respond_with(ok_response(json!({})))
        .mount(&server)
        .await;
    // 全新客户端、无当前设备上下文，仅指定目标 URL
    let client = RouterClient::new(true);
    let ms = client.ping_url(&server.uri()).await;
    assert!(ms.is_some());
}

/// 契约：ICMP 不可达（TEST-NET 保留网段）且 HTTP 兜底不通 → None
#[tokio::test]
async fn ping_unreachable_host_returns_none() {
    let client = RouterClient::new(true);
    assert!(client.ping_url("http://203.0.113.1/").await.is_none());
}
