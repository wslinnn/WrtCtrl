//! session 语义快照测试：登录/预检重登/重连契约。

use serde_json::{json, Value};
use std::time::Duration;
use wiremock::matchers::{body_partial_json, method};
use wiremock::{Match, Mock, MockServer, Request, ResponseTemplate};
use wrtctrl_core::error::UbusError;
use wrtctrl_core::rpc::{DeviceSession, RouterClient, EMPTY_SESSION};
use wrtctrl_core::session::{LoginError, PROBE_TIMEOUT};

const FIVE_SECONDS: Duration = Duration::from_secs(5);

/// 精确匹配一次 ubus call 的 object/method（params = [session, object, method, args]）；
/// body_partial_json 对数组要求全等，无法只看 object/method，故自定义
struct UbusCall(&'static str, &'static str);

impl Match for UbusCall {
    fn matches(&self, request: &Request) -> bool {
        request
            .body_json::<Value>()
            .ok()
            .and_then(|v| {
                let params = v["params"].as_array()?;
                Some(
                    params.len() >= 3
                        && params[1] == json!(self.0)
                        && params[2] == json!(self.1),
                )
            })
            .unwrap_or(false)
    }
}

async fn client_to(server: &MockServer, session: &str) -> RouterClient {
    let client = RouterClient::new(true);
    client
        .set_session(Some(DeviceSession {
            base_url: server.uri(),
            session: Some(session.into()),
            username: "root".into(),
            password: "pw".into(),
        }))
        .await;
    client
}

fn login_response(session: &str) -> ResponseTemplate {
    ResponseTemplate::new(200).set_body_json(json!({
        "jsonrpc": "2.0", "id": 1,
        "result": [0, {"ubus_rpc_session": session, "timeout": 86400}]
    }))
}

/// 契约：两段式登录——以 EMPTY_SESSION 调 session.login，取 ubus_rpc_session 并就地换会话
#[tokio::test]
async fn login_swaps_session_in_place() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(body_partial_json(json!({
            "params": [EMPTY_SESSION, "session", "login", {"username": "root", "password": "pw"}]
        })))
        .respond_with(login_response("fresh-session"))
        .mount(&server)
        .await;
    // 登录后新会话可探针成功（证明会话已就地更换）
    Mock::given(method("POST"))
        .and(body_partial_json(json!({"params": ["fresh-session", "system", "board", {}]})))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({"result": [0, {}]})))
        .mount(&server)
        .await;
    let client = client_to(&server, EMPTY_SESSION).await;

    let session = client.login().await.unwrap();
    assert_eq!(session, "fresh-session");
    client.probe().await.unwrap();
}

/// 契约：凭证错误（rpcd result[0]=6 Permission denied）→ Auth
#[tokio::test]
async fn bad_credentials_map_to_auth() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({"result": [6]})))
        .mount(&server)
        .await;
    let client = client_to(&server, EMPTY_SESSION).await;

    let err = client.login().await.unwrap_err();
    assert!(matches!(err, LoginError::Auth(6)), "got {err:?}");
}

/// 契约：登录超时 → Timeout
#[tokio::test]
async fn login_timeout_maps() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_json(json!({"result": [0, {}]}))
                .set_delay(Duration::from_secs(10)), // 明显大于 LOGIN_TIMEOUT(5s)，避免竞争
        )
        .mount(&server)
        .await;
    let client = RouterClient::new(true);
    client
        .set_session(Some(DeviceSession {
            base_url: server.uri(),
            session: None,
            username: "root".into(),
            password: "pw".into(),
        }))
        .await;

    let err = tokio::time::timeout(FIVE_SECONDS, client.login())
        .await
        .unwrap()
        .unwrap_err();
    assert!(matches!(err, LoginError::Timeout), "got {err:?}");
}

/// 契约：ensure_session——探针失效 → 静默重登换新会话（防 apply 后 confirm 过期被回滚）
#[tokio::test]
async fn ensure_session_relogins_on_expired() {
    let server = MockServer::start().await;
    // 旧会话已失效
    Mock::given(method("POST"))
        .and(body_partial_json(json!({"params": ["stale", "system", "board", {}]})))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({"result": [6]})))
        .mount(&server)
        .await;
    // 用存储凭证重登
    Mock::given(method("POST"))
        .and(UbusCall("session", "login"))
        .respond_with(login_response("fresh"))
        .mount(&server)
        .await;
    // 新会话探针成功
    Mock::given(method("POST"))
        .and(body_partial_json(json!({"params": ["fresh", "system", "board", {}]})))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({"result": [0, {}]})))
        .mount(&server)
        .await;
    let client = client_to(&server, "stale").await;

    client.ensure_session().await.unwrap();
    // 预检后旧会话已换成 fresh
    client.probe().await.unwrap();
}

/// 契约：探针成功则不触发重登（login 期望 0 次）
#[tokio::test]
async fn ensure_session_skips_login_when_probe_ok() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(body_partial_json(json!({"params": ["ok", "system", "board", {}]})))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({"result": [0, {}]})))
        .mount(&server)
        .await;
    Mock::given(method("POST"))
        .and(UbusCall("session", "login"))
        .respond_with(login_response("should-not-happen"))
        .expect(0)
        .mount(&server)
        .await;
    let client = client_to(&server, "ok").await;

    client.ensure_session().await.unwrap();
    server.verify().await;
}

/// 契约：reconnect——探活失败 → 静默重登 → 返回新会话
#[tokio::test]
async fn reconnect_relogins_after_failure() {
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(body_partial_json(json!({"params": ["stale", "system", "board", {}]})))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({"result": [12]})))
        .mount(&server)
        .await;
    Mock::given(method("POST"))
        .and(UbusCall("session", "login"))
        .respond_with(login_response("fresh"))
        .mount(&server)
        .await;
    let client = client_to(&server, "stale").await;

    let session = client.reconnect().await.unwrap();
    assert_eq!(session, "fresh");
}

/// 契约：连接拒绝（端口不通）→ Network（区别于 Auth/Timeout）
#[tokio::test]
async fn connect_refused_is_network_error() {
    // 占用一个端口后立即释放，得到一个几乎必然无人监听的端口
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    drop(listener);

    let client = RouterClient::new(true);
    client
        .set_session(Some(DeviceSession {
            base_url: format!("http://127.0.0.1:{port}"),
            session: Some("s".into()),
            username: "root".into(),
            password: "pw".into(),
        }))
        .await;

    let err = client.reconnect().await.unwrap_err();
    assert!(matches!(err, LoginError::Network(_)), "got {err:?}");
}

/// 契约：探针超时常量 = 3s（固定值，勿改）
#[test]
fn probe_timeout_is_three_seconds() {
    assert_eq!(PROBE_TIMEOUT, Duration::from_secs(3));
}

/// 编译期兜底：UbusError → LoginError 的映射对每个变体可达
#[test]
fn ubus_error_mapping_preserves_variants() {
    assert!(matches!(
        LoginError::from(UbusError::Ubus(6)),
        LoginError::Auth(6)
    ));
    assert!(matches!(
        LoginError::from(UbusError::Timeout),
        LoginError::Timeout
    ));
    assert!(matches!(
        LoginError::from(UbusError::NoDevice),
        LoginError::NoDevice
    ));
    assert!(matches!(
        LoginError::from(UbusError::Network("x".into())),
        LoginError::Network(_)
    ));
}
