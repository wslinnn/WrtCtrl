package dev.wrtctrl.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 应用中心注册表纯函数：可见性判定与两段结构（对齐 LuCI 应用列表）。
 *  全部走生产路径 visibleSections。 */
class AppRegistryTest {

    private val ids: (List<AppItem>) -> List<String> = { list -> list.map { it.id } }

    @Test
    fun `visibleSections——插件段在前工具段收后、探测过滤、既有行序`() {
        // 全部安装：插件段（network→storage→system 组内原序）在前，工具段收后
        val installed = AppRegistry.probeConfigs.associateWith { true }
        val (plugins, tools) = AppRegistry.visibleSections(installed)
        assertEquals(
            listOf("arpbind", "firewall", "upnp", "wolultra", "samba4", "cifs", "usb-printer", "autoreboot"),
            ids(plugins),
        )
        assertEquals(
            listOf("diag", "conntrack", "syslog", "process", "route", "startup", "reboot"),
            ids(tools),
        )
        // 探测失败：upnp 从插件段消失，其余不动
        val (pluginsPartial, toolsPartial) = AppRegistry.visibleSections(mapOf("upnpd" to false))
        assertFalse(ids(pluginsPartial).contains("upnp"))
        assertEquals(tools, toolsPartial)
    }

    @Test
    fun `tools always visible regardless of probe result`() {
        val tools = AppRegistry.visibleSections(emptyMap()).second
        assertEquals(listOf("diag", "conntrack", "syslog", "process", "route", "startup", "reboot"), ids(tools))
    }

    @Test
    fun `firewall fixed visible without probe`() {
        val plugins = AppRegistry.visibleSections(emptyMap()).first
        assertTrue(ids(plugins).contains("firewall"))
    }

    @Test
    fun `probe failure hides non-fixed plugin`() {
        val plugins = ids(AppRegistry.visibleSections(mapOf("upnpd" to false)).first)
        assertFalse(plugins.contains("upnp"))
        assertFalse(plugins.contains("arpbind"))
    }

    @Test
    fun `probe success shows plugin`() {
        val plugins = ids(AppRegistry.visibleSections(mapOf("upnpd" to true, "samba4" to true)).first)
        assertTrue(plugins.contains("upnp"))
        assertTrue(plugins.contains("samba4"))
        assertFalse(plugins.contains("cifs"))
    }

    @Test
    fun `all probes failed leaves only fixed plugin and tools`() {
        // 全部探测失败：只剩 firewall(fixed) + 全部工具
        val (plugins, tools) = AppRegistry.visibleSections(emptyMap())
        assertEquals(listOf("firewall"), ids(plugins))
        assertEquals(7, tools.size)
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
