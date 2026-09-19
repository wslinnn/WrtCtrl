package dev.wrtctrl

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import dev.wrtctrl.data.ThemeMode
import dev.wrtctrl.data.ThemePrefs
import kotlinx.coroutines.runBlocking
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
        // 启动恢复深浅色覆盖（阻塞读小文件，毫秒级；不恢复则首帧会闪错主题）
        runCatching {
            val mode = runBlocking { ThemePrefs(this@WrtApp).current() }
            AppCompatDelegate.setDefaultNightMode(
                when (mode) {
                    ThemeMode.FOLLOW_SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                    ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                },
            )
        }
    }
}
