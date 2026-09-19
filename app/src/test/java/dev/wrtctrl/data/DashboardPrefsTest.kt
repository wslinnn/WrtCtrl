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
    fun `已存顺序保留且缺失 id 按默认序补尾`() {
        val config = DashboardPrefs.parse(
            orderRaw = "NETWORK,SYSTEM,RESOURCE",
            enabledRaw = "RESOURCE,NETWORK,SYSTEM,BANDWIDTH,STORAGE",
            collapsedRaw = "SYSTEM",
        )
        assertEquals(
            listOf(
                DashboardCardId.NETWORK,
                DashboardCardId.SYSTEM,
                DashboardCardId.RESOURCE,
                DashboardCardId.BANDWIDTH,
                DashboardCardId.STORAGE,
            ),
            config.order,
        )
        assertEquals(DashboardCardId.SYSTEM, config.collapsed.single())
    }

    @Test
    fun `非法 id 丢弃且重复去重`() {
        val config = DashboardPrefs.parse("BOGUS,SYSTEM,SYSTEM", "BOGUS", null)
        assertEquals(
            listOf(
                DashboardCardId.SYSTEM,
                DashboardCardId.RESOURCE,
                DashboardCardId.BANDWIDTH,
                DashboardCardId.NETWORK,
                DashboardCardId.STORAGE,
            ),
            config.order,
        )
    }

    @Test
    fun `collapsed 键缺省回落默认收起集而非空`() {
        // 老版本升级场景：order/enabled 已存、collapsed 尚无 → 明细三卡默认收起
        val config = DashboardPrefs.parse(
            orderRaw = DashboardCardId.entries.joinToString(",") { it.name },
            enabledRaw = DashboardCardId.entries.joinToString(",") { it.name },
            collapsedRaw = null,
        )
        assertEquals(
            setOf(DashboardCardId.SYSTEM, DashboardCardId.NETWORK, DashboardCardId.STORAGE),
            config.collapsed,
        )
    }

    @Test
    fun `collapsed 非法 id 丢弃`() {
        val config = DashboardPrefs.parse(null, null, collapsedRaw = "BOGUS,RESOURCE")
        assertTrue(DashboardCardId.RESOURCE in config.collapsed)
        assertEquals(1, config.collapsed.size)
    }
}
