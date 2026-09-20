package dev.wrtctrl.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardPrefsTest {

    @Test
    fun `全空输入回落默认配置`() {
        val config = DashboardPrefs.parse(null, null)
        assertEquals(DashboardCardId.entries.toList(), config.order)
        assertEquals(DashboardCardId.entries.toSet(), config.enabled)
        assertEquals(DashboardPrefs.DEFAULT.collapsed, config.collapsed)
    }

    @Test
    fun `老配置里已除名id丢弃且缺失id按默认序补尾`() {
        // 升级场景：旧五卡持久化（含 SYSTEM/STORAGE）→ 三卡保留用户自定顺序
        val config = DashboardPrefs.parse(
            orderRaw = "NETWORK,SYSTEM,RESOURCE",
            enabledRaw = "RESOURCE,NETWORK,SYSTEM,BANDWIDTH,STORAGE",
            collapsedRaw = "SYSTEM",
        )
        assertEquals(
            listOf(
                DashboardCardId.NETWORK,
                DashboardCardId.RESOURCE,
                DashboardCardId.BANDWIDTH,
            ),
            config.order,
        )
        assertTrue(config.collapsed.isEmpty())
    }

    @Test
    fun `非法 id 丢弃且重复去重`() {
        val config = DashboardPrefs.parse("BOGUS,NETWORK,NETWORK", "BOGUS", null)
        assertEquals(
            listOf(
                DashboardCardId.NETWORK,
                DashboardCardId.BANDWIDTH,
                DashboardCardId.RESOURCE,
            ),
            config.order,
        )
    }

    @Test
    fun `collapsed 键缺省回落默认空集而非旧收起集`() {
        // 老版本升级场景：order/enabled 已存、collapsed 尚无 → 新默认全展开
        val config = DashboardPrefs.parse(
            orderRaw = DashboardCardId.entries.joinToString(",") { it.name },
            enabledRaw = DashboardCardId.entries.joinToString(",") { it.name },
            collapsedRaw = null,
        )
        assertEquals(emptySet<DashboardCardId>(), config.collapsed)
    }

    @Test
    fun `collapsed 非法 id 丢弃`() {
        val config = DashboardPrefs.parse(null, null, collapsedRaw = "BOGUS,RESOURCE")
        assertTrue(DashboardCardId.RESOURCE in config.collapsed)
        assertEquals(1, config.collapsed.size)
    }

    @Test
    fun `enabled 解析为空视同未配置全开`() {
        // 旧 enabled 恰好只含已除名 id（SYSTEM/STORAGE）→ 不允许首页被清空
        val config = DashboardPrefs.parse(
            orderRaw = "BANDWIDTH,RESOURCE,NETWORK",
            enabledRaw = "SYSTEM,STORAGE",
            collapsedRaw = null,
        )
        assertEquals(DashboardCardId.entries.toSet(), config.enabled)
    }
}
