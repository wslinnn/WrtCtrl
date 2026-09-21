package dev.wrtctrl.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 防火墙 zone 颜色哈希单测：lan/wan 为 luci 已验证锚点值（.4） */
class FirewallColorsTest {

    @Test
    fun `luci known colors`() {
        assertEquals("#90f090", FirewallColors.zoneColor("lan"))
        assertEquals("#f09090", FirewallColors.zoneColor("wan"))
    }

    @Test
    fun `neutral for blank and star`() {
        assertEquals("#bbbbbb", FirewallColors.zoneColor(null))
        assertEquals("#bbbbbb", FirewallColors.zoneColor(""))
        assertEquals("#bbbbbb", FirewallColors.zoneColor("*"))
    }

    @Test
    fun `derived color deterministic and well formed`() {
        val a = FirewallColors.zoneColor("dmz")
        val b = FirewallColors.zoneColor("dmz")
        assertEquals(a, b)
        assertTrue(a.matches(Regex("^#[0-9a-f]{6}$")))
    }

    @Test
    fun `distinct names derive distinct colors`() {
        val names = listOf("guest", "iot", "vpn", "dmz", "office", "lab")
        assertEquals(names.size, names.map { FirewallColors.zoneColor(it) }.toSet().size)
    }
}
