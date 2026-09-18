package dev.wrtctrl.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class DashboardCardId { RESOURCE, BANDWIDTH, SYSTEM, NETWORK, STORAGE }

data class DashboardConfig(
    val order: List<DashboardCardId>,
    val enabled: Set<DashboardCardId>,
)

// 独立 DataStore 文件：与 wrtctrl（设备仓储）互不触碰，避免同文件单例冲突
private val Context.dashboardStore by preferencesDataStore(name = "dashboard")

/** 仪表盘卡片配置持久化：order 为全序（5 卡恒在列表），enabled 决定渲染时是否出现 */
class DashboardPrefs(private val context: Context) {

    fun configFlow(): Flow<DashboardConfig> = context.dashboardStore.data.map { prefs ->
        parse(prefs[ORDER_KEY], prefs[ENABLED_KEY])
    }

    suspend fun save(config: DashboardConfig) {
        context.dashboardStore.edit { prefs ->
            prefs[ORDER_KEY] = config.order.joinToString(",") { it.name }
            prefs[ENABLED_KEY] = config.enabled.joinToString(",") { it.name }
        }
    }

    companion object {
        private val ORDER_KEY = stringPreferencesKey("card_order")
        private val ENABLED_KEY = stringPreferencesKey("card_enabled")

        val DEFAULT = DashboardConfig(
            order = DashboardCardId.entries.toList(),
            enabled = DashboardCardId.entries.toSet(),
        )

        /** 容错解析：非法 id 丢弃、缺失的 id 按默认序补尾（版本演进兼容），enabled 缺省全开 */
        fun parse(orderRaw: String?, enabledRaw: String?): DashboardConfig {
            if (orderRaw.isNullOrBlank() && enabledRaw.isNullOrBlank()) return DEFAULT
            val order = (orderRaw?.split(",") ?: emptyList())
                .mapNotNull { runCatching { DashboardCardId.valueOf(it.trim()) }.getOrNull() }
                .distinct()
                .let { parsed -> parsed + DEFAULT.order.filterNot { it in parsed } }
            val enabled = (enabledRaw?.split(",") ?: emptyList())
                .mapNotNull { runCatching { DashboardCardId.valueOf(it.trim()) }.getOrNull() }
                .toSet()
            return DashboardConfig(order, enabled)
        }
    }
}
