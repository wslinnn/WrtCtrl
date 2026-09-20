package dev.wrtctrl.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 应用中心注册表纯函数：可见性判定与分组（对齐 LuCI 应用列表） */
class AppRegistryTest {

    private val ids: (List<AppItem>) -> List<String> = { list -> list.map { it.id } }

    @Test
    fun `tools always visible regardless of probe result`() {
        val groups = AppRegistry.visibleGroups(emptyMap())
        val tools = groups.first { it.first == AppGroupId.TOOLS }.second
        assertEquals(listOf("route", "process", "startup", "diag", "syslog", "conntrack", "reboot"), ids(tools))
    }

    @Test
    fun `firewall fixed visible without probe`() {
        val groups = AppRegistry.visibleGroups(emptyMap())
        val network = groups.first { it.first == AppGroupId.NETWORK }.second
        assertTrue("firewall".let { id -> ids(network).contains(id) })
    }

    @Test
    fun `probe failure hides non-fixed plugin`() {
        val groups = AppRegistry.visibleGroups(mapOf("upnpd" to false))
        val network = ids(groups.first { it.first == AppGroupId.NETWORK }.second)
        assertFalse(network.contains("upnp"))
        assertTrue(network.contains("arpbind").not())
    }

    @Test
    fun `probe success shows plugin`() {
        val groups = AppRegistry.visibleGroups(mapOf("upnpd" to true, "samba4" to true))
        val network = ids(groups.first { it.first == AppGroupId.NETWORK }.second)
        assertTrue(network.contains("upnp"))
        val storage = ids(groups.first { it.first == AppGroupId.STORAGE }.second)
        assertTrue(storage.contains("samba4"))
        assertFalse(storage.contains("cifs"))
    }

    @Test
    fun `empty group omitted and group order stable`() {
        // 全部探测失败：只剩 tools + firewall(fixed network)；storage/system 组消失
        val groups = AppRegistry.visibleGroups(emptyMap())
        assertEquals(listOf(AppGroupId.TOOLS, AppGroupId.NETWORK), groups.map { it.first })
    }

    @Test
    fun `passwall2 absent from registry`() {
        val all = AppRegistry.tools + AppRegistry.plugins
        assertFalse(ids(all).contains("passwall2"))
        assertFalse(AppRegistry.probeConfigs.contains("passwall2"))
        // proxy 组不复存在
        assertFalse(AppGroupId.entries.any { it.name == "PROXY" })
    }

    @Test
    fun `probe configs distinct and complete`() {
        // 8 插件中 7 个需探测（firewall fixed），config 互不重复
        assertEquals(listOf("arpbind", "upnpd", "wolultra", "samba4", "cifs-mount", "usb_printer", "autoreboot"), AppRegistry.probeConfigs)
    }
}
