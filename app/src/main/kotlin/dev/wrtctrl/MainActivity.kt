package dev.wrtctrl

import android.os.Bundle
import androidx.activity.compose.setContent
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
        super.onCreate(savedInstanceState)
        setContent {
            WrtTheme {
                AppRoot()
            }
        }
    }
}
