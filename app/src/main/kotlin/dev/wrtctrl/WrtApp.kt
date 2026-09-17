package dev.wrtctrl

import android.app.Application
import android.util.Log
import java.io.File

/**
 * 崩溃陷阱：未捕获异常的堆栈写入 filesDir/last_crash.txt，
 * 下次启动在界面上展示（无 adb 环境时也能拿到闪退原因）。
 */
class WrtApp : Application() {
    override fun onCreate() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                File(filesDir, "last_crash.txt").writeText(
                    buildString {
                        appendLine("thread=${thread.name}")
                        appendLine(Log.getStackTraceString(throwable))
                    },
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
        super.onCreate()
    }
}
