package dev.wrtctrl.viewmodel

import dev.wrtctrl.R

/**
 * 应用中心注册表（与 LuCI 应用列表一致的 TOOLS/PLUGINS/GROUPS 三张表）。
 *
 * - TOOLS：固定系统工具，恒显示；页面在 落地，落地前点击 toast「即将推出」。
 * - PLUGINS：luci 插件；fixed=true 恒显示，否则按 uci get <probeConfig> 探测结果显隐。
 *   
 * - 名称来自 locale（appNameRes / pluginTitleKey），图标语义映射 Material Icons。
 */
enum class AppGroupId(val titleRes: Int) {
    TOOLS(R.string.apps_group_tools),
    NETWORK(R.string.apps_group_network),
    STORAGE(R.string.apps_group_storage),
    SYSTEM(R.string.apps_group_system),
}

data class AppEntry(
    val id: String,
    val group: AppGroupId,
    /** 探测用 uci config；null = 恒显示（工具 / fixed 插件） */
    val probeConfig: String?,
)

data class AppDisplay(
    val entry: AppEntry,
    /** 图标 Material 名（imageVector 由 Screen 层解析，保持本文件无 compose 依赖可单测） */
    val icon: String,
    /** 无合适图标的用缩写文字（UPnP/WOL，对齐旧 abbr） */
    val abbr: String? = null,
)

object AppRegistry {
    val tools = listOf(
        AppDisplay(AppEntry("route", AppGroupId.TOOLS, null), "Route"),
        AppDisplay(AppEntry("process", AppGroupId.TOOLS, null), "Memory"),
        AppDisplay(AppEntry("startup", AppGroupId.TOOLS, null), "Power"),
        AppDisplay(AppEntry("diag", AppGroupId.TOOLS, null), "MonitorHeart"),
        AppDisplay(AppEntry("syslog", AppGroupId.TOOLS, null), "Description"),
        AppDisplay(AppEntry("conntrack", AppGroupId.TOOLS, null), "Link"),
        AppDisplay(AppEntry("reboot", AppGroupId.TOOLS, null), "RestartAlt"),
    )

    val plugins = listOf(
        AppDisplay(AppEntry("arpbind", AppGroupId.NETWORK, "arpbind"), "PushPin"),
        AppDisplay(AppEntry("firewall", AppGroupId.NETWORK, null), "Shield"),
        AppDisplay(AppEntry("upnp", AppGroupId.NETWORK, "upnpd"), "Router"),
        AppDisplay(AppEntry("wolultra", AppGroupId.NETWORK, "wolultra"), "WifiTethering", abbr = "WOL"),
        AppDisplay(AppEntry("samba4", AppGroupId.STORAGE, "samba4"), "Share"),
        AppDisplay(AppEntry("cifs", AppGroupId.STORAGE, "cifs-mount"), "HardDrive"),
        AppDisplay(AppEntry("usb-printer", AppGroupId.STORAGE, "usb_printer"), "Print"),
        AppDisplay(AppEntry("autoreboot", AppGroupId.SYSTEM, "autoreboot"), "Schedule"),
    )

    /** 全部需要探测的 config 名（并行 uci get 的清单，去重保序） */
    val probeConfigs: List<String> =
        (tools + plugins).mapNotNull { it.entry.probeConfig }.distinct()

    /**
     * 可见项分组（纯函数，单测覆盖）：tools 恒显；插件 fixed（probeConfig=null）恒显，
     * 否则 installed[config]==true 才显；空组不出现。组序按 AppGroupId 声明序。
     */
    fun visibleGroups(installed: Map<String, Boolean>): List<Pair<AppGroupId, List<AppDisplay>>> =
        AppGroupId.entries.mapNotNull { group ->
            val apps = (tools + plugins)
                .filter { it.entry.group == group }
                .filter { it.entry.probeConfig == null || installed[it.entry.probeConfig] == true }
            if (apps.isEmpty()) null else group to apps
        }
}
