package dev.wrtctrl

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import dev.wrtctrl.ui.app.AppRoot
import dev.wrtctrl.ui.theme.WrtTheme

/**
 * 唯一 Activity。基类必须是 AppCompatActivity：
 * per-app language（AppCompatDelegate.setApplicationLocales）在
 * Android 12 及以下仅对 AppCompatActivity 生效。
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // targetSdk 35 强制 edge-to-edge：必须显式启用，让系统栏透明且
        // 图标深浅色跟随日夜模式（否则浅色图标配浅色背景直接隐形）
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            WrtTheme {
                AppRoot()
            }
        }
    }
}
