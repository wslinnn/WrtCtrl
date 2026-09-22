#![allow(dead_code)]
//! 集成测试共享工具：ubus call 匹配器、客户端构造、响应模板、请求序列断言。

use serde_json::{json, Value};
use wiremock::{Match, Mock, MockServer, Request, ResponseTemplate};
use wrtctrl_core::rpc::{DeviceSession, RouterClient};

/// 精确匹配一次 ubus call 的 object/method（params = [session, object, method, args]）；
/// body_partial_json 对数组要求全等，无法只看 object/method，故自定义
pub struct UbusCall(pub &'static str, pub &'static str);

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

pub async fn client_to(server: &MockServer, session: &str) -> RouterClient {
    let client = RouterClient::new(true);
    client
        .set_session(Some(DeviceSession {
            base_url: server.uri(),
            session: Some(session.into()),
            username: "root".into(),
            password: "pw".into(),
            expected_cert_sha256: None,
        }))
        .await;
    client
}

pub fn ok_response(payload: Value) -> ResponseTemplate {
    ResponseTemplate::new(200).set_body_json(json!({
        "jsonrpc": "2.0", "id": 1, "result": [0, payload]
    }))
}

/// result[0]=0 且无载荷
pub fn ok_empty() -> ResponseTemplate {
    ResponseTemplate::new(200).set_body_json(json!({"jsonrpc": "2.0", "id": 1, "result": [0]}))
}

pub fn ubus_err_response(code: i64) -> ResponseTemplate {
    ResponseTemplate::new(200).set_body_json(json!({"jsonrpc": "2.0", "id": 1, "result": [code]}))
}

/// 提取匹配 object/method 的最近一次 ubus call 的 params 数组
pub async fn last_call_params(server: &MockServer, object: &str, method: &str) -> Value {
    let calls: Vec<Value> = ubus_call_sequence(server).await;
    calls
        .into_iter()
        .rev()
        .find(|params| params[1] == json!(object) && params[2] == json!(method))
        .expect("未找到匹配的 ubus 调用")
}

/// 按请求顺序返回每次 ubus call 的 [session, object, method, args] 数组
pub async fn ubus_call_sequence(server: &MockServer) -> Vec<Value> {
    let mut calls = Vec::new();
    for req in server.received_requests().await.unwrap_or_default() {
        let Ok(v) = serde_json::from_slice::<Value>(&req.body) else {
            continue;
        };
        let Some(params) = v.get("params").and_then(|p| p.as_array()) else {
            continue;
        };
        if params.len() >= 3 {
            calls.push(json!(params));
        }
    }
    calls
}

/// 挂载一个兜底 mock（匹配任意 POST，返回 result[0]=0 无载荷）
pub async fn mount_catch_all(server: &MockServer) {
    Mock::given(wiremock::matchers::method("POST"))
        .respond_with(ok_empty())
        .mount(server)
        .await;
}
