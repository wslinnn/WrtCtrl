//! callUbus 语义快照测试：每条用例锚定一条行为契约。

use serde_json::{json, Value};
use std::time::{Duration, Instant};
use wiremock::matchers::{body_partial_json, method};
use wiremock::{Mock, MockServer, ResponseTemplate};
use wrtctrl_core::error::UbusError;
use wrtctrl_core::rpc::{DeviceSession, RouterClient, EMPTY_SESSION};

async fn client_with_session(server: &MockServer, session: Option<&str>) -> RouterClient {
    let client = RouterClient::new(true);
    client
        .set_session(Some(DeviceSession {
            base_url: server.uri(),
            session: session.map(str::to_string),
            username: "root".into(),
            password: "pw".into(),
            expected_cert_sha256: None,
        }))
        .await;
    client
}

/// 契约：POST {base}/ubus，params = [session, object, method, params]，成功取 result[1]
#[tokio::test]
async fn success_returns_result_payload() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(body_partial_json(json!({
            "method": "call",
            "params": ["test-session", "system", "board", {}]
        })))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "jsonrpc": "2.0",
            "id": 1,
            "result": [0, {"model": "XiaoMi AX3000T", "release": {"version": "23.05"}}]
        })))
        .mount(&server)
        .await;
    let client = client_with_session(&server, Some("test-session")).await;

    let value = client
        .call_ubus("system", "board", json!({}), Duration::from_secs(5))
        .await
        .unwrap();

    assert_eq!(value["model"], "XiaoMi AX3000T");
    assert_eq!(value["release"]["version"], "23.05");
}

/// 契约：result[0] != 0 → 透传 ubus 错误码（6 = Permission denied）
#[tokio::test]
async fn ubus_error_code_passthrough() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "jsonrpc": "2.0", "id": 1, "result": [6]
        })))
        .mount(&server)
        .await;
    let client = client_with_session(&server, Some("s")).await;

    let err = client
        .call_ubus("uci", "get", json!({"config": "wireless"}), Duration::from_secs(5))
        .await
        .unwrap_err();

    assert!(matches!(err, UbusError::Ubus(6)), "got {err:?}");
}

/// 契约：硬超时兜底——"连接已建立但服务端不响应"也必须最终失败，且快速失败
#[tokio::test]
async fn hard_timeout_on_silent_server() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_json(json!({"result": [0]}))
                .set_delay(Duration::from_secs(5)),
        )
        .mount(&server)
        .await;
    let client = client_with_session(&server, Some("s")).await;

    let start = Instant::now();
    let err = client
        .call_ubus("file", "exec", json!({"command": "/bin/hang"}), Duration::from_millis(300))
        .await
        .unwrap_err();
    let elapsed = start.elapsed();

    assert!(matches!(err, UbusError::Timeout), "got {err:?}");
    assert!(elapsed < Duration::from_secs(2), "timeout must fail fast, took {elapsed:?}");
}

/// 契约：HTTP 层错误/非 JSON 体 → InvalidResponse（带状态码，便于排障）
#[tokio::test]
async fn http_error_or_garbage_body_is_invalid_response() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .respond_with(ResponseTemplate::new(500).set_body_string("boom"))
        .mount(&server)
        .await;
    let client = client_with_session(&server, Some("s")).await;

    let err = client
        .call_ubus("system", "info", json!({}), Duration::from_secs(5))
        .await
        .unwrap_err();

    assert!(matches!(err, UbusError::InvalidResponse(ref m) if m.contains("500")), "got {err:?}");
}

/// 契约：未设置当前设备 → NoDevice（不发出任何网络请求）
#[tokio::test]
async fn no_device_configured_fails_fast() {
    let client = RouterClient::new(true);
    let err = client
        .call_ubus("system", "board", json!({}), Duration::from_secs(1))
        .await
        .unwrap_err();
    assert!(matches!(err, UbusError::NoDevice));
}

/// 契约：session 为 None（未登录）时以全 0 临时会话调用（session.login 的前置）
#[tokio::test]
async fn null_session_uses_empty_session() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(body_partial_json(json!({"params": [EMPTY_SESSION, "session", "login", {"username": "root"}]})))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({"result": [0, {"ubus_rpc_session": "abc"}]})))
        .mount(&server)
        .await;
    let client = client_with_session(&server, None).await;

    let value = client
        .call_ubus("session", "login", json!({"username": "root", "password": "pw"}), Duration::from_secs(5))
        .await
        .unwrap();

    assert_eq!(value["ubus_rpc_session"], "abc");
}

/// 契约：result[0]=0 且无载荷（部分 ubus 调用如此）→ Ok(Null)
#[tokio::test]
async fn result_without_payload_returns_null() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "jsonrpc": "2.0", "id": 1, "result": [0]
        })))
        .mount(&server)
        .await;
    let client = client_with_session(&server, Some("s")).await;

    let value = client
        .call_ubus("system", "reboot", json!({}), Duration::from_secs(5))
        .await
        .unwrap();

    assert_eq!(value, Value::Null);
}
