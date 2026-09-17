package dev.wrtctrl.bridge

/**
 * Rust core 的 JNI 入口封装。
 * 库名 wrtctrl_jni（由 crates/wrtctrl-jni 经 cargo-ndk 构建产出 libwrtctrl_jni.so）。
 * panic 防线：所有导出在 Rust 侧 catch_unwind；panicTest 是演练入口。
 */
object WrtCore {
    init {
        System.loadLibrary("wrtctrl_jni")
    }

    /** 初始：返回 core 版本信息 */
    external fun hello(): String

    /** 返回 "caught panic: ..." = 防线生效；进程直接死掉 = 防线失效（必须修） */
    external fun panicTest(): String
}
