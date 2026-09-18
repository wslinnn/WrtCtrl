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
    /** 收起态卡片集合：跨重启记忆（rememberSaveable 只能活过 Activity 记录期，划掉重开即失） */
    val collapsed: Set<DashboardCardId>,
)

// 独立 DataStore 文件：与 wrtctrl（设备仓储）互不触碰，避免同文件单例冲突
private val Context.dashboardStore by preferencesDataStore(name = "dashboard")

/** 仪表盘卡片配置持久化：order 为全序（5 卡恒在列表），enabled 决定渲染时是否出现，collapsed 决定展开态 */
class DashboardPrefs(private val context: Context) {

    fun configFlow(): Flow<DashboardConfig> = context.dashboardStore.data.map { prefs ->
        parse(prefs[ORDER_KEY], prefs[ENABLED_KEY], prefs[COLLAPSED_KEY])
    }

    suspend fun save(config: DashboardConfig) {
        context.dashboardStore.edit { prefs ->
            prefs[ORDER_KEY] = config.order.joinToString(",") { it.name }
            prefs[ENABLED_KEY] = config.enabled.joinToString(",") { it.name }
            prefs[COLLAPSED_KEY] = config.collapsed.joinToString(",") { it.name }
        }
    }

    companion object {
        private val ORDER_KEY = stringPreferencesKey("card_order")
        private val ENABLED_KEY = stringPreferencesKey("card_enabled")
        private val COLLAPSED_KEY = stringPreferencesKey("card_collapsed")

        val DEFAULT = DashboardConfig(
            order = DashboardCardId.entries.toList(),
            enabled = DashboardCardId.entries.toSet(),
            collapsed = setOf(DashboardCardId.SYSTEM, DashboardCardId.NETWORK, DashboardCardId.STORAGE),
        )

        /** 容错解析：非法 id 丢弃、缺失的 id 按默认序补尾（版本演进兼容），enabled 缺省全开，
         *  collapsed 键缺省=默认收起集；升级后新出现的卡类型不在已存集合中 → 展开态 */
        fun parse(orderRaw: String?, enabledRaw: String?, collapsedRaw: String? = null): DashboardConfig {
            if (orderRaw.isNullOrBlank() && enabledRaw.isNullOrBlank() && collapsedRaw.isNullOrBlank()) return DEFAULT
            val order = (orderRaw?.split(",") ?: emptyList())
                .mapNotNull { runCatching { DashboardCardId.valueOf(it.trim()) }.getOrNull() }
                .distinct()
                .let { parsed -> parsed + DEFAULT.order.filterNot { it in parsed } }
            val enabled = (enabledRaw?.split(",") ?: emptyList())
                .mapNotNull { runCatching { DashboardCardId.valueOf(it.trim()) }.getOrNull() }
                .toSet()
            val collapsed = if (collapsedRaw.isNullOrBlank()) {
                DEFAULT.collapsed
            } else {
                collapsedRaw.split(",")
                    .mapNotNull { runCatching { DashboardCardId.valueOf(it.trim()) }.getOrNull() }
                    .toSet()
            }
            return DashboardConfig(order, enabled, collapsed)
        }
    }
}
