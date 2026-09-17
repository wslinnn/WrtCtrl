//! uci 语义快照测试：覆盖 rpcd uci 语义坑位的契约测试。

mod common;

use common::{client_to, last_call_params, ok_response, ubus_err_response, ubus_call_sequence, UbusCall};
use serde_json::json;
use wiremock::{Mock, MockServer};
use wrtctrl_core::error::UbusError;

// ── uci.get ──

/// 契约：get 解包 .values，.type/.anonymous/.name 元数据键归位，过滤非 section 条目
#[tokio::test]
async fn get_unwraps_values_and_builds_sections() {
    let server = MockServer::start().await;
    Mock::given(UbusCall("uci", "get"))
        .respond_with(ok_response(json!({
            "values": {
                "lan": {".type": "interface", ".anonymous": false, ".name": "lan",
                        "proto": "static", "ipaddr": ["192.168.1.1"]},
                "cfg2": {".type": "rule", ".anonymous": true, ".name": "cfg2", "enabled": "1"},
                "not_a_section": "scalar-junk"
            }
        })))
        .mount(&server)
        .await;
    let client = client_to(&server, "s").await;

    let sections = client.uci_get("network").await.unwrap();

    assert_eq!(sections.len(), 2);
    let lan = &sections["lan"];
    assert_eq!(lan.name, "lan");
    assert_eq!(lan.section_type, "interface");
    assert!(!lan.anonymous);
    assert_eq!(lan.options["proto"], "static");
    assert_eq!(lan.options["ipaddr"][0], "192.168.1.1");
    assert!(sections["cfg2"].anonymous);
}

/// 契约：响应无 .values 包装时从载荷直接解析（rpcd 版本差异防御，hasValues 分支行为）
#[tokio::test]
async fn get_falls_back_to_flat_payload() {
    let server = MockServer::start().await;
    Mock::given(UbusCall("uci", "get"))
        .respond_with(ok_response(json!({
            "wan": {".type": "interface", ".anonymous": false, ".name": "wan"}
        })))
        .mount(&server)
        .await;
    let client = client_to(&server, "s").await;

    let sections = client.uci_get("network").await.unwrap();
    assert_eq!(sections.len(), 1);
    assert_eq!(sections["wan"].name, "wan");
}

// ── uci.add ──

/// 契约：add 只传 config+type，绝不传 name/values（rpcd 带值会 INVALID_ARGS[2]）；
/// 返回新 section 名
#[tokio::test]
async fn add_sends_only_config_and_type() {
    let server = MockServer::start().await;
    Mock::given(UbusCall("uci", "add"))
        .respond_with(ok_response(json!({"section": "cfg123456"})))
        .mount(&server)
        .await;
    let client = client_to(&server, "s").await;

    let name = client.uci_add("firewall", "rule").await.unwrap();

    assert_eq!(name, "cfg123456");
    let params = last_call_params(&server, "uci", "add").await;
    assert_eq!(params[3], json!({"config": "firewall", "type": "rule"}));
}

/// 契约：add 响应缺 section → InvalidResponse（旧 `|| res` 兜底为死分支噪音，显式报错）
#[tokio::test]
async fn add_missing_section_is_invalid_response() {
    let server = MockServer::start().await;
    Mock::given(UbusCall("uci", "add"))
        .respond_with(ok_response(json!({})))
        .mount(&server)
        .await;
    let client = client_to(&server, "s").await;

    let err = client.uci_add("firewall", "rule").await.unwrap_err();
    assert!(matches!(err, UbusError::InvalidResponse(_)), "got {err:?}");
}

// ── uci.set ──

/// 契约：空 list 就地剔除（rpcd uci.set 不接受空数组；与 LuCI 语义一致）
#[tokio::test]
async fn set_strips_empty_lists() {
    let server = MockServer::start().await;
    Mock::given(UbusCall("uci", "set"))
        .respond_with(ok_response(json!({})))
        .mount(&server)
        .await;
    let client = client_to(&server, "s").await;

    client
        .uci_set(
            "samba4",
            "cfg1",
            json!({"name": "share", "users": ["root"], "guest_ok": []}),
        )
        .await
        .unwrap();

    let params = last_call_params(&server, "uci", "set").await;
    assert_eq!(
        params[3],
        json!({"config": "samba4", "section": "cfg1", "values": {"name": "share", "users": ["root"]}})
    );
}

/// 契约：非对象 values 本地拒绝（零网络请求）
#[tokio::test]
async fn set_rejects_non_object_values() {
    let server = MockServer::start().await;
    let client = client_to(&server, "s").await;

    let err = client.uci_set("system", "@system[0]", json!("scalar")).await.unwrap_err();
    assert!(matches!(err, UbusError::InvalidArgument(_)), "got {err:?}");
    assert!(server.received_requests().await.unwrap_or_default().is_empty());
}

// ── uci.order ──

/// 契约：rpcd 方法名是 order（ACL 不授予 reorder），按目标顺序全量列出 sections
#[tokio::test]
async fn order_uses_order_method_with_full_list() {
    let server = MockServer::start().await;
    Mock::given(UbusCall("uci", "order"))
        .respond_with(ok_response(json!({})))
        .mount(&server)
        .await;
    let client = client_to(&server, "s").await;

    client
        .uci_order("firewall", &["c".into(), "a".into(), "b".into()])
        .await
        .unwrap();

    let params = last_call_params(&server, "uci", "order").await;
    assert_eq!(params[2], "order");
    assert_eq!(
        params[3],
        json!({"config": "firewall", "sections": ["c", "a", "b"]})
    );
}

// ── uci_commit（安全写路径核心）──

/// 契约：commit = 预检（失效→重登）→ apply{rollback:true,timeout:120} → confirm，
/// 且 apply/confirm 用重登后的新会话
#[tokio::test]
async fn commit_ensures_session_then_applies_with_rollback_and_confirms() {
    let server = MockServer::start().await;
    // 预检发现旧会话失效
    Mock::given(UbusCall("system", "board"))
        .respond_with(ubus_err_response(6))
        .mount(&server)
        .await;
    // 存储凭证重登
    Mock::given(UbusCall("session", "login"))
        .respond_with(ok_response(json!({"ubus_rpc_session": "fresh"})))
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
    let client = client_to(&server, "stale").await;

    client.uci_commit("firewall").await.unwrap();

    let apply = last_call_params(&server, "uci", "apply").await;
    assert_eq!(apply[0], json!("fresh"));
    assert_eq!(
        apply[3],
        json!({"config": "firewall", "rollback": true, "timeout": 120})
    );
    let confirm = last_call_params(&server, "uci", "confirm").await;
    assert_eq!(confirm[0], json!("fresh"));
    assert_eq!(confirm[3], json!({"config": "firewall"}));

    let sequence: Vec<String> = ubus_call_sequence(&server)
        .await
        .iter()
        .map(|p| p[2].as_str().unwrap().to_string())
        .collect();
    assert_eq!(sequence, ["board", "login", "apply", "confirm"]);
}

/// 契约：预检通过则不重登（login 0 次），apply/confirm 用现有会话
#[tokio::test]
async fn commit_with_valid_session_skips_relogin() {
    let server = MockServer::start().await;
    Mock::given(UbusCall("system", "board"))
        .respond_with(ok_response(json!({})))
        .mount(&server)
        .await;
    Mock::given(UbusCall("session", "login"))
        .respond_with(ok_response(json!({"ubus_rpc_session": "nope"})))
        .expect(0)
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
    let client = client_to(&server, "valid").await;

    client.uci_commit("firewall").await.unwrap();

    let apply = last_call_params(&server, "uci", "apply").await;
    assert_eq!(apply[0], json!("valid"));
    server.verify().await;
}

/// 契约：回滚窗口常量 = 120s（跨网 confirm 余量，勿改）
#[test]
fn rollback_timeout_is_120() {
    assert_eq!(wrtctrl_core::uci::ROLLBACK_TIMEOUT_SECS, 120);
}

// ── 组合便捷方法 ──

/// 契约：set_commit = set + commit（不含 apply，apply 由调用方按需再调）
#[tokio::test]
async fn set_commit_chains_set_then_commit() {
    let server = MockServer::start().await;
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
    let client = client_to(&server, "s").await;

    client
        .uci_set_commit("system", "@system[0]", json!({"hostname": "AX3000T"}))
        .await
        .unwrap();

    let set_params = last_call_params(&server, "uci", "set").await;
    assert_eq!(
        set_params[3],
        json!({"config": "system", "section": "@system[0]", "values": {"hostname": "AX3000T"}})
    );
    let sequence: Vec<String> = ubus_call_sequence(&server)
        .await
        .iter()
        .map(|p| p[2].as_str().unwrap().to_string())
        .collect();
    assert_eq!(sequence, ["set", "board", "apply", "confirm"]);
}

/// 契约：add_commit = add → set(有值时) → commit，返回新 section 名
#[tokio::test]
async fn add_commit_sets_values_and_returns_name() {
    let server = MockServer::start().await;
    Mock::given(UbusCall("uci", "add"))
        .respond_with(ok_response(json!({"section": "cfg1"})))
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
    let client = client_to(&server, "s").await;

    let name = client
        .uci_add_commit(
            "firewall",
            "rule",
            json!({"name": "allow-ssh", "src": "wan", "proto": "tcp", "dest_port": ["22"]}),
        )
        .await
        .unwrap();

    assert_eq!(name, "cfg1");
    let set_params = last_call_params(&server, "uci", "set").await;
    assert_eq!(set_params[3]["section"], json!("cfg1"));
    assert_eq!(
        set_params[3]["values"],
        json!({"name": "allow-ssh", "src": "wan", "proto": "tcp", "dest_port": ["22"]})
    );
    let sequence: Vec<String> = ubus_call_sequence(&server)
        .await
        .iter()
        .map(|p| p[2].as_str().unwrap().to_string())
        .collect();
    assert_eq!(sequence, ["add", "set", "board", "apply", "confirm"]);
}

/// 契约：add_commit 空值时跳过 set（add 空壳已足够）
#[tokio::test]
async fn add_commit_with_empty_values_skips_set() {
    let server = MockServer::start().await;
    Mock::given(UbusCall("uci", "add"))
        .respond_with(ok_response(json!({"section": "cfg9"})))
        .mount(&server)
        .await;
    Mock::given(UbusCall("uci", "set"))
        .respond_with(ok_response(json!({})))
        .expect(0)
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
    let client = client_to(&server, "s").await;

    let name = client.uci_add_commit("firewall", "rule", json!({})).await.unwrap();
    assert_eq!(name, "cfg9");
    server.verify().await;
}

/// 契约：delete_commit = delete + commit
#[tokio::test]
async fn delete_commit_chains_delete_then_commit() {
    let server = MockServer::start().await;
    Mock::given(UbusCall("uci", "delete"))
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
    let client = client_to(&server, "s").await;

    client.uci_delete_commit("dhcp", "cfg3").await.unwrap();

    let delete_params = last_call_params(&server, "uci", "delete").await;
    assert_eq!(
        delete_params[3],
        json!({"config": "dhcp", "section": "cfg3"})
    );
    let sequence: Vec<String> = ubus_call_sequence(&server)
        .await
        .iter()
        .map(|p| p[2].as_str().unwrap().to_string())
        .collect();
    assert_eq!(sequence, ["delete", "board", "apply", "confirm"]);
}
