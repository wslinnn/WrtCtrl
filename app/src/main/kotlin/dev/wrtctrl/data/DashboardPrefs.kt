package dev.wrtctrl.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 可折叠卡三件（UI 改版 P1 收敛：SYSTEM 解体为身份区+资源环、STORAGE 除名——
 * 旧持久化里的已除名 id 经 parse 容错丢弃）。枚举顺序即默认卡片顺序。
 */
enum class DashboardCardId { BANDWIDTH, RESOURCE, NETWORK }

data class DashboardConfig(
    val order: List<DashboardCardId>,
    val enabled: Set<DashboardCardId>,
    /** 收起态卡片集合：跨重启记忆（rememberSaveable 只能活过 Activity 记录期，划掉重开即失） */
    val collapsed: Set<DashboardCardId>,
)

// 独立 DataStore 文件：与 wrtctrl（设备仓储）互不触碰，避免同文件单例冲突
private val Context.dashboardStore by preferencesDataStore(name = "dashboard")

/** 仪表盘卡片配置持久化：order 为全序（3 卡恒在列表），enabled 决定渲染时是否出现，collapsed 决定展开态 */
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
            collapsed = emptySet(),
        )

        /** 容错解析：非法/已除名 id 丢弃、缺失的 id 按默认序补尾
         *  （版本演进兼容），enabled 缺省全开；enabled 解析后为空视同未配置（全开），
         *  防旧配置恰好只含已除名 id 时首页被清空；collapsed 键缺省=全展开 */
        fun parse(orderRaw: String?, enabledRaw: String?, collapsedRaw: String? = null): DashboardConfig {
            if (orderRaw.isNullOrBlank() && enabledRaw.isNullOrBlank() && collapsedRaw.isNullOrBlank()) return DEFAULT
            val order = (orderRaw?.split(",") ?: emptyList())
                .mapNotNull { runCatching { DashboardCardId.valueOf(it.trim()) }.getOrNull() }
                .distinct()
                .let { parsed -> parsed + DEFAULT.order.filterNot { it in parsed } }
            val enabled = (enabledRaw?.split(",") ?: emptyList())
                .mapNotNull { runCatching { DashboardCardId.valueOf(it.trim()) }.getOrNull() }
                .toSet()
                .ifEmpty { DEFAULT.enabled }
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
