//! wrtctrl-jni：JNI 导出层。
//!
//! panic 防线（必须维持）：每个导出函数体用 catch_unwind 包裹，
//! 保证 Rust panic 不会跨 FFI 边界导致进程 abort（Rust ≥1.81 起未捕获
//! panic 跨 extern ABI 是确定性 abort）。panic hook 输出到 logcat（tag=wrtctrl）。

use jni::objects::JClass;
use jni::sys::jstring;
use jni::JNIEnv;
use std::os::raw::{c_char, c_int};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::Once;

static INIT_HOOK: Once = Once::new();

const LOG_PRIORITY_ERROR: c_int = 6;

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

/// 把 Result<jstring, _> 归一为 jstring（panic 或 JNI 错误时返回 null）。
fn to_jstring(env: &mut JNIEnv, text: String) -> jstring {
    env.new_string(text)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_dev_wrtctrl_bridge_WrtCore_hello(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    init_panic_hook();
    match catch_unwind(AssertUnwindSafe(|| wrtctrl_core::hello())) {
        Ok(text) => to_jstring(&mut env, text),
        Err(_) => to_jstring(&mut env, "ERROR: core hello panicked".to_string()),
    }
}

/// panic 演练入口：core 主动 panic，验证边界防线。
/// 返回 "caught panic: ..." = 防线生效；若进程直接死掉 = 防线失效。
#[no_mangle]
pub extern "system" fn Java_dev_wrtctrl_bridge_WrtCore_panicTest(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    init_panic_hook();
    let text = match catch_unwind(AssertUnwindSafe(wrtctrl_core::panic_drill)) {
        Ok(_) => "UNEXPECTED: panic_drill did not panic".to_string(),
        Err(payload) => match payload.downcast_ref::<&str>() {
            Some(m) => format!("caught panic: {m}"),
            None => "caught panic: <non-str payload>".to_string(),
        },
    };
    to_jstring(&mut env, text)
}
