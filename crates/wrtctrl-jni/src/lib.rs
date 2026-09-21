//! wrtctrl-jni：JNI 导出层。
//!
//! 桥接模式：
//! 全部导出为阻塞式（内部 block_on tokio），Kotlin 侧用 Dispatchers.IO 包装。
//! 当前所有调用都是对答式（无服务端推送场景），回调机制属过度设计——
//! 且可免去 AttachCurrentThread/GlobalRef 一整类崩溃源。
//!
//! panic 防线（必须维持）：每个导出经 guarded! 巨集 catch_unwind 包裹，
//! Rust panic 转为 JSON 错误信封而非进程 abort（Rust ≥1.81 跨 ABI 未捕获
//! panic 是确定性 abort）。panic hook 输出 logcat（tag=wrtctrl）。
//!
//! 应答信封（全部导出统一）：
//!   成功 {"ok":true,"data":<载荷>}；失败 {"ok":false,"error":{"code","message","ubus"?}}

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jstring};
use jni::JNIEnv;
use serde::Serialize;
use serde_json::{json, Value};
use std::os::raw::{c_char, c_int};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::{Arc, Once, OnceLock};
use std::time::Duration;
use tokio::runtime::Runtime;
use wrtctrl_core::error::UbusError;
use wrtctrl_core::rpc::RouterClient;
use wrtctrl_core::session::LoginError;

static INIT_HOOK: Once = Once::new();
static RUNTIME: OnceLock<Runtime> = OnceLock::new();
static CLIENT: OnceLock<Arc<RouterClient>> = OnceLock::new();

const LOG_PRIORITY_ERROR: i32 = 6;

extern "C" {
    fn __android_log_print(prio: c_int, tag: *const c_char, fmt: *const c_char, ...) -> c_int;
}

fn init_panic_hook() {
    INIT_HOOK.call_once(|| {
        std::panic::set_hook(Box::new(|info| {
            let msg = info.to_string().replace('%', "%%");
            if let Ok(cmsg) = std::ffi::CString::new(msg) {
                let tag = b"wrtctrl\0";
                unsafe {
                    __android_log_print(LOG_PRIORITY_ERROR, tag.as_ptr().cast(), cmsg.as_ptr());
                }
            }
        }));
    });
}

fn runtime() -> &'static Runtime {
    RUNTIME.get_or_init(|| {
        Runtime::new().expect("tokio runtime init failed")
    })
}

/// 全局唯一客户端。初始 accept_invalid_certs=true 仅为占位——首个 setDevice 的
/// set_session 会按设备 baseUrl scheme 派生 TLS 策略重建会话客户端
/// （自签 HTTPS 放行 = 旧行为；no_proxy 直连；重定向禁用见 core build_http）
fn client() -> &'static Arc<RouterClient> {
    CLIENT.get_or_init(|| Arc::new(RouterClient::new(true)))
}

// ── JNI 工具 ──

fn jstr(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s)
        .map(|v| v.to_string_lossy().into_owned())
        .unwrap_or_default()
}

fn parse_json(text: &str) -> Result<Value, UbusError> {
    serde_json::from_str(text)
        .map_err(|e| UbusError::InvalidArgument(format!("bad json: {e}")))
}

/// 网络错误细分：从错误链关键词归类，供 UI 层给出可行动的指引
/// （dns=解析失败[典型：域名仅 IPv6 而当前网络无 v6]、refused=端口/设备不可达、
///  tls=TLS 握手层失败）。token 集与 LoginError 转换共用 core 的
/// error::classify_network_chain（单一实现）。
fn network_code(chain: &str) -> &'static str {
    match wrtctrl_core::error::classify_network_chain(chain) {
        wrtctrl_core::error::NetFailureKind::Dns => "dns",
        wrtctrl_core::error::NetFailureKind::Refused => "refused",
        wrtctrl_core::error::NetFailureKind::Tls => "tls",
        wrtctrl_core::error::NetFailureKind::Other => "network",
    }
}

fn error_json(e: &UbusError) -> Value {
    let (code, ubus) = match e {
        UbusError::Network(chain) => (network_code(chain), None),
        UbusError::Timeout => ("timeout", None),
        UbusError::Ubus(code) => ("ubus", Some(*code)),
        UbusError::InvalidResponse(_) => ("invalid_response", None),
        UbusError::InvalidArgument(_) => ("invalid_argument", None),
        UbusError::NoDevice => ("no_device", None),
    };
    let mut v = json!({"code": code, "message": e.to_string()});
    if let Some(code) = ubus {
        v["ubus"] = json!(code);
    }
    v
}

/// 登录错误专用信封：保留 auth/certificate 语义（经 UbusError 转换会压平成
/// 通用 ubus 码，UI 层"认证失败/网络失败"的分支就失效了）
fn login_error_json(e: &LoginError) -> Value {
    let (code, ubus) = match e {
        LoginError::Auth(c) => ("auth", Some(*c)),
        LoginError::Timeout => ("timeout", None),
        LoginError::Certificate(_) => ("certificate", None),
        LoginError::Network(chain) => (network_code(chain), None),
        LoginError::InvalidResponse(_) => ("invalid_response", None),
        LoginError::NoDevice => ("no_device", None),
    };
    let mut v = json!({"code": code, "message": e.to_string()});
    if let Some(code) = ubus {
        v["ubus"] = json!(code);
    }
    v
}

fn respond_login<T: Serialize>(env: &mut JNIEnv, result: Result<T, LoginError>) -> jstring {
    let value = match result {
        Ok(data) => json!({"ok": true, "data": data}),
        Err(e) => json!({"ok": false, "error": login_error_json(&e)}),
    };
    env.new_string(value.to_string())
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn respond<T: Serialize>(env: &mut JNIEnv, result: Result<T, UbusError>) -> jstring {
    let value = match result {
        Ok(data) => json!({"ok": true, "data": data}),
        Err(e) => json!({"ok": false, "error": error_json(&e)}),
    };
    env.new_string(value.to_string())
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn respond_err(env: &mut JNIEnv, code: &str, message: &str) -> jstring {
    // 直接构造信封而不是经 UbusError 归一——panic 等边界错误必须保留独立 code
    // （压平成 invalid_response 会迫使 Kotlin 侧字符串前缀嗅探，正是 error.rs
    // 评审收敛要消灭的模式）
    let value = json!({"ok": false, "error": {"code": code, "message": message}});
    env.new_string(value.to_string())
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// 统一导出样板：panic 防线 + block_on + 应答信封。
/// panic 错误以独立 code="panic" 返回（进程不 abort）。
macro_rules! guarded {
    ($env:expr, $body:expr) => {{
        init_panic_hook();
        let mut env = $env;
        let result = match catch_unwind(AssertUnwindSafe(|| runtime().block_on($body))) {
            Ok(result) => result,
            Err(_) => {
                return respond_err(&mut env, "panic", "caught panic at the JNI boundary");
            }
        };
        respond(&mut env, result)
    }};
}

/// 登录/重连专用：错误信封保留 auth/certificate 语义
macro_rules! guarded_login {
    ($env:expr, $body:expr) => {{
        init_panic_hook();
        let mut env = $env;
        let result = match catch_unwind(AssertUnwindSafe(|| runtime().block_on($body))) {
            Ok(result) => result,
            Err(_) => {
                return respond_login::<Value>(
                    &mut env,
                    Err(LoginError::InvalidResponse(
                        "panic: caught at the JNI boundary".into(),
                    )),
                );
            }
        };
        respond_login(&mut env, result)
    }};
}

/// 超时入参校验：0/负数会构成立即触发的 timeout（无意义 Timeout 错误），
/// 与 Kotlin 侧「未传」语义混淆——显式拒绝而非静默钳位
fn millis(ms: jint) -> Result<Duration, UbusError> {
    if ms <= 0 {
        return Err(UbusError::InvalidArgument(
            "timeout_ms must be positive".into(),
        ));
    }
    Ok(Duration::from_millis(ms as u64))
}

// ── 导出 ──

#[no_mangle]
pub extern "system" fn Java_dev_wrtctrl_bridge_WrtCore_helloNative(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    guarded!(env, async { Ok(wrtctrl_core::hello()) })
}

/// panic 演练：返回 "caught panic: ..." = 防线生效；进程死掉 = 防线失效
#[no_mangle]
pub extern "system" fn Java_dev_wrtctrl_bridge_WrtCore_panicTestNative(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    init_panic_hook();
    let text = match catch_unwind(AssertUnwindSafe(wrtctrl_core::panic_drill)) {
        Ok(_) => "UNEXPECTED: panic_drill did not panic".to_string(),
        Err(payload) => {
            let msg = payload
                .downcast_ref::<&str>()
                .map(|m| m.to_string())
                .or_else(|| payload.downcast_ref::<String>().cloned())
                .unwrap_or_else(|| "<non-str payload>".to_string());
            format!("caught panic: {msg}")
        }
    };
    env.new_string(text)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// 设置/切换当前设备上下文。cfg: {"baseUrl","username","password","session"(可省)}
#[no_mangle]
pub extern "system" fn Java_dev_wrtctrl_bridge_WrtCore_setDeviceNative(
    mut env: JNIEnv,
    _class: JClass,
    cfg_json: JString,
) -> jstring {
    let cfg = jstr(&mut env, &cfg_json);
    guarded!(env, async move {
        let v = parse_json(&cfg)?;
        let obj = v.as_object().ok_or(UbusError::InvalidArgument(
            "setDevice cfg must be an object".into(),
        ))?;
        let base_url = obj
            .get("baseUrl")
            .and_then(|v| v.as_str())
            .ok_or(UbusError::InvalidArgument("baseUrl required".into()))?
            .to_string();
        let get_str = |key: &str| {
            obj.get(key)
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string()
        };
        let session = wrtctrl_core::DeviceSession {
            base_url,
            session: obj.get("session").and_then(|v| v.as_str()).map(str::to_string),
            username: get_str("username"),
            password: get_str("password"),
        };
        client().set_session(Some(session)).await;
        // 信封统一为 data:null（此前 data={"ok":true} 与外层 ok 双层重复且无消费者）
        Ok(())
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_wrtctrl_bridge_WrtCore_loginNative(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    guarded_login!(env, async {
        client()
            .login()
            .await
            .map(|session| json!({"session": session}))
    })
}

/// 重连：探活当前会话，失败自动用存储凭证重登
#[no_mangle]
pub extern "system" fn Java_dev_wrtctrl_bridge_WrtCore_reconnectNative(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    guarded_login!(env, async {
        client()
            .reconnect()
            .await
            .map(|session| json!({"session": session}))
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_wrtctrl_bridge_WrtCore_callUbusNative(
    mut env: JNIEnv,
    _class: JClass,
    object: JString,
    method: JString,
    params_json: JString,
    timeout_ms: jint,
) -> jstring {
    let (object, method, params_json) = (jstr(&mut env, &object), jstr(&mut env, &method), jstr(&mut env, &params_json));
    guarded!(env, async move {
        let params = parse_json(&params_json)?;
        client()
            .call_ubus(&object, &method, params, millis(timeout_ms)?)
            .await
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_wrtctrl_bridge_WrtCore_uciGetNative(
    mut env: JNIEnv,
    _class: JClass,
    config: JString,
) -> jstring {
    let config = jstr(&mut env, &config);
    guarded!(env, async move { client().uci_get(&config).await })
}

#[no_mangle]
pub extern "system" fn Java_dev_wrtctrl_bridge_WrtCore_uciAddNative(
    mut env: JNIEnv,
    _class: JClass,
    config: JString,
    section_type: JString,
) -> jstring {
    let (config, section_type) = (jstr(&mut env, &config), jstr(&mut env, &section_type));
    guarded!(env, async move { client().uci_add(&config, &section_type).await })
}

#[no_mangle]
pub extern "system" fn Java_dev_wrtctrl_bridge_WrtCore_uciSetNative(
    mut env: JNIEnv,
    _class: JClass,
    config: JString,
    section: JString,
    values_json: JString,
) -> jstring {
    let (config, section, values_json) = (
        jstr(&mut env, &config),
        jstr(&mut env, &section),
        jstr(&mut env, &values_json),
    );
    guarded!(env, async move {
        let values = parse_json(&values_json)?;
        client().uci_set(&config, &section, values).await
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_wrtctrl_bridge_WrtCore_uciDeleteNative(
    mut env: JNIEnv,
    _class: JClass,
    config: JString,
    section: JString,
) -> jstring {
    let (config, section) = (jstr(&mut env, &config), jstr(&mut env, &section));
    guarded!(env, async move { client().uci_delete(&config, &section).await })
}

#[no_mangle]
pub extern "system" fn Java_dev_wrtctrl_bridge_WrtCore_uciOrderNative(
    mut env: JNIEnv,
    _class: JClass,
    config: JString,
    sections_json: JString,
) -> jstring {
    let (config, sections_json) = (jstr(&mut env, &config), jstr(&mut env, &sections_json));
    guarded!(env, async move {
        let sections: Vec<String> = parse_json(&sections_json)?
            .as_array()
            .ok_or(UbusError::InvalidArgument("sections must be an array".into()))?
            .iter()
            .map(|v| {
                v.as_str()
                    .map(str::to_string)
                    .ok_or_else(|| UbusError::InvalidArgument("sections must be strings".into()))
            })
            .collect::<Result<Vec<_>, _>>()?;
        client().uci_order(&config, &sections).await
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_wrtctrl_bridge_WrtCore_uciCommitNative(
    mut env: JNIEnv,
    _class: JClass,
    config: JString,
) -> jstring {
    let config = jstr(&mut env, &config);
    guarded!(env, async move { client().uci_commit(&config).await })
}

#[no_mangle]
pub extern "system" fn Java_dev_wrtctrl_bridge_WrtCore_applyNative(
    mut env: JNIEnv,
    _class: JClass,
    init_script: JString,
    action: JString,
) -> jstring {
    let (init_script, action) = (jstr(&mut env, &init_script), jstr(&mut env, &action));
    guarded!(env, async move { client().apply(&init_script, &action).await })
}

/// 候选查询：kind ∈ hosthints/devices/interfaces/zones/printers/helpers/ipsets/ifaddrs
/// + iwinfo 实时枚举四类（freqlist/htmodes/txpowerlist/countrylist，param=device 即 radio section 名）
#[no_mangle]
pub extern "system" fn Java_dev_wrtctrl_bridge_WrtCore_candidatesNative(
    mut env: JNIEnv,
    _class: JClass,
    kind: JString,
    param: JString,
) -> jstring {
    let (kind, param) = (jstr(&mut env, &kind), jstr(&mut env, &param));
    let kind_str = kind.as_str();
    guarded!(env, async move {
        let client = client();
        Ok(match kind_str {
            "hosthints" => serde_json::to_value(client.get_host_hint_candidates().await)?,
            "devices" => serde_json::to_value(client.get_device_candidates().await)?,
            "interfaces" => serde_json::to_value(client.get_interface_candidates().await)?,
            "zones" => serde_json::to_value(client.get_zone_candidates().await)?,
            "printers" => serde_json::to_value(client.get_usb_printers().await)?,
            // 全量对齐恢复/新增
            "helpers" => serde_json::to_value(client.get_conntrack_helpers().await)?,
            "ipsets" => serde_json::to_value(client.get_ipset_candidates().await)?,
            "ifaddrs" => serde_json::to_value(client.get_ifaddr_candidates().await)?,
            // wifi 编辑器实时枚举（param 空时返回空列表，上层回退静态枚举）
            "freqlist" | "htmodes" | "txpowerlist" | "countrylist" => {
                serde_json::to_value(client.get_iwinfo_candidates(kind_str, &param).await)?
            }
            other => {
                return Err(UbusError::InvalidArgument(format!(
                    "unknown candidates kind: {other}"
                )))
            }
        })
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_wrtctrl_bridge_WrtCore_readFileNative(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jstring {
    let path = jstr(&mut env, &path);
    guarded!(env, async move { client().read_file(&path).await })
}

#[no_mangle]
pub extern "system" fn Java_dev_wrtctrl_bridge_WrtCore_writeFileNative(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
    data: JString,
    mode: JString,
) -> jstring {
    let (path, data, mode) = (jstr(&mut env, &path), jstr(&mut env, &data), jstr(&mut env, &mode));
    guarded!(env, async move {
        // mode 空串 = 不携带（合法）；非空但解析失败显式报错——十进制契约
        // （core 集成测试锚定 420=0644），静默丢弃会让 chmod 请求无痕迹失效
        let mode = if mode.is_empty() {
            None
        } else {
            Some(mode.parse::<u32>().map_err(|_| {
                UbusError::InvalidArgument(format!("bad mode: {mode} (decimal expected)"))
            })?)
        };
        client().write_file(&path, &data, mode).await
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_wrtctrl_bridge_WrtCore_readSyslogNative(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    guarded!(env, async { client().read_syslog().await })
}

#[no_mangle]
pub extern "system" fn Java_dev_wrtctrl_bridge_WrtCore_readDmesgNative(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    guarded!(env, async { client().read_dmesg().await })
}

#[no_mangle]
pub extern "system" fn Java_dev_wrtctrl_bridge_WrtCore_wirelessStatusNative(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    guarded!(env, async { Ok(client().get_status().await) })
}

#[no_mangle]
pub extern "system" fn Java_dev_wrtctrl_bridge_WrtCore_assocListNative(
    mut env: JNIEnv,
    _class: JClass,
    ifname: JString,
) -> jstring {
    let ifname = jstr(&mut env, &ifname);
    guarded!(env, async { Ok(client().get_assoc_list(&ifname).await) })
}

#[no_mangle]
pub extern "system" fn Java_dev_wrtctrl_bridge_WrtCore_setRadioEnabledNative(
    mut env: JNIEnv,
    _class: JClass,
    radio_name: JString,
    enabled: jboolean,
) -> jstring {
    let radio_name = jstr(&mut env, &radio_name);
    guarded!(env, async move {
        client().set_radio_enabled(&radio_name, enabled != 0).await
    })
}

#[no_mangle]
pub extern "system" fn Java_dev_wrtctrl_bridge_WrtCore_restartRadioNative(
    mut env: JNIEnv,
    _class: JClass,
    radio_name: JString,
) -> jstring {
    let radio_name = jstr(&mut env, &radio_name);
    guarded!(env, async move {
        // 归一为 {code,stdout,stderr}（对齐三个 diag 导出的 exec_result_json 形状），
        // 不再原样透传 file.exec 载荷（同类操作两种信封形状）
        let payload = client().restart_radio(&radio_name).await?;
        Ok(json!({
            "code": payload.get("code").cloned().unwrap_or(Value::Null),
            "stdout": payload.get("stdout").cloned().unwrap_or(Value::Null),
            "stderr": payload.get("stderr").cloned().unwrap_or(Value::Null),
        }))
    })
}

/// 探活任意设备（设备列表并行 ping；当前设备同经此以 baseUrl 发起）→ {"ms": 123} 或 {"ms": null}
#[no_mangle]
pub extern "system" fn Java_dev_wrtctrl_bridge_WrtCore_pingUrlNative(
    mut env: JNIEnv,
    _class: JClass,
    base_url: JString,
) -> jstring {
    let base_url = jstr(&mut env, &base_url);
    guarded!(env, async move {
        let ms = client().ping_url(&base_url).await;
        Ok(json!({"ms": ms}))
    })
}

// ── 诊断：必须走 core diag——clean_host 的 argv 净化在 core 层，Kotlin 侧
//    不得用裸 callUbus file.exec 绕过 ──

fn exec_result_json(r: wrtctrl_core::diag::ExecResult) -> Value {
    json!({"code": r.code, "stdout": r.stdout, "stderr": r.stderr})
}

/// ping：host + count/wait/deadline（0 值回落 core 默认）
#[no_mangle]
pub extern "system" fn Java_dev_wrtctrl_bridge_WrtCore_diagPingNative(
    mut env: JNIEnv,
    _class: JClass,
    host: JString,
    count: jint,
    wait: jint,
    deadline: jint,
) -> jstring {
    let host = jstr(&mut env, &host);
    guarded!(env, async move {
        let r = client()
            .ping(&host, count.max(0) as u32, wait.max(0) as u32, deadline.max(0) as u32)
            .await?;
        Ok(exec_result_json(r))
    })
}

/// traceroute：host + maxHops/wait/queries（0 值回落 core 默认；IPv6 目标自动走 traceroute6）
#[no_mangle]
pub extern "system" fn Java_dev_wrtctrl_bridge_WrtCore_diagTracerouteNative(
    mut env: JNIEnv,
    _class: JClass,
    host: JString,
    max_hops: jint,
    wait: jint,
    queries: jint,
) -> jstring {
    let host = jstr(&mut env, &host);
    guarded!(env, async move {
        let r = client()
            .traceroute(&host, max_hops.max(0) as u32, wait.max(0) as u32, queries.max(0) as u32)
            .await?;
        Ok(exec_result_json(r))
    })
}

/// nslookup：host + 可选 DNS 服务器（空串 = 不指定）
#[no_mangle]
pub extern "system" fn Java_dev_wrtctrl_bridge_WrtCore_diagNslookupNative(
    mut env: JNIEnv,
    _class: JClass,
    host: JString,
    dns_server: JString,
) -> jstring {
    let (host, dns_server) = (jstr(&mut env, &host), jstr(&mut env, &dns_server));
    guarded!(env, async move {
        let dns = if dns_server.is_empty() { None } else { Some(dns_server.as_str()) };
        let r = client().nslookup(&host, dns).await?;
        Ok(exec_result_json(r))
    })
}
