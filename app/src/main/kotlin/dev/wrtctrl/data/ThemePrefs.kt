package dev.wrtctrl.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeMode { FOLLOW_SYSTEM, DARK, LIGHT }

private val Context.themeStore by preferencesDataStore(name = "theme")

/** 深浅色三态偏好持久化（跟随系统/深色/浅色）。
 *  应用侧经 AppCompatDelegate.setDefaultNightMode 全局生效：WrtApp 启动时阻塞恢复
 *  （小文件毫秒级，避免首帧闪错主题），运行期变更由 AppRoot 的 flow 收集驱动。 */
class ThemePrefs(private val context: Context) {

    fun modeFlow(): Flow<ThemeMode> = context.themeStore.data.map { prefs ->
        val raw = prefs[MODE_KEY] ?: return@map ThemeMode.FOLLOW_SYSTEM
        runCatching { ThemeMode.valueOf(raw) }.getOrDefault(ThemeMode.FOLLOW_SYSTEM)
    }

    suspend fun current(): ThemeMode = modeFlow().first()

    suspend fun save(mode: ThemeMode) {
        context.themeStore.edit { it[MODE_KEY] = mode.name }
    }

    companion object {
        private val MODE_KEY = stringPreferencesKey("theme_mode")

        /** 三态循环顺序：跟随系统 → 深色 → 浅色 → … */
        fun next(mode: ThemeMode): ThemeMode = when (mode) {
            ThemeMode.FOLLOW_SYSTEM -> ThemeMode.DARK
            ThemeMode.DARK -> ThemeMode.LIGHT
            ThemeMode.LIGHT -> ThemeMode.FOLLOW_SYSTEM
        }
    }
}
