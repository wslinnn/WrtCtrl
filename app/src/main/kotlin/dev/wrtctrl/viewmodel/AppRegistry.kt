package dev.wrtctrl.viewmodel

import androidx.annotation.StringRes
import dev.wrtctrl.R

/**
 * 应用中心注册表（与 LuCI 应用列表一致的 TOOLS/PLUGINS/GROUPS 三张表）。
 *
 * - TOOLS：固定系统工具，恒显示。
 * - PLUGINS：luci 插件；probeConfig=null 恒显示（fixed），否则按 uci get <probeConfig>
 *   探测结果显隐。
 * - 名称走 nameRes（插件复用各自 *_title 键），图标语义映射 Material Icons（AppScreen
 *   解析 imageVector，保持本文件无 compose 依赖可单测）。
 */
enum class AppGroupId(val titleRes: Int) {
    TOOLS(R.string.apps_group_tools),
    NETWORK(R.string.apps_group_network),
    STORAGE(R.string.apps_group_storage),
    SYSTEM(R.string.apps_group_system),
}

/** 注册表单条目（纯数据）：探测键 + 屏层展示。新增入口只改本表一处 */
data class AppItem(
    val id: String,
    val group: AppGroupId,
    /** 探测用 uci config；null = 恒显示（工具 / fixed 插件） */
    val probeConfig: String?,
    @StringRes val nameRes: Int,
    /** 图标 Material 名（AppScreen.resolveIcon 解析；现值须与该 when 分支一字不差） */
    val icon: String,
    /** 无合适图标的用缩写文字（UPnP/WOL，对齐旧 abbr） */
    val abbr: String? = null,
)

object AppRegistry {
    val tools = listOf(
        AppItem("route", AppGroupId.TOOLS, null, R.string.apps_route, "Route"),
        AppItem("process", AppGroupId.TOOLS, null, R.string.apps_process, "Memory"),
        AppItem("startup", AppGroupId.TOOLS, null, R.string.apps_startup, "Power"),
        AppItem("diag", AppGroupId.TOOLS, null, R.string.apps_diag, "MonitorHeart"),
        AppItem("syslog", AppGroupId.TOOLS, null, R.string.apps_syslog, "Description"),
        AppItem("conntrack", AppGroupId.TOOLS, null, R.string.apps_conntrack, "Link"),
        AppItem("reboot", AppGroupId.TOOLS, null, R.string.apps_reboot, "RestartAlt"),
    )

    val plugins = listOf(
        AppItem("arpbind", AppGroupId.NETWORK, "arpbind", R.string.arpbind_title, "PushPin"),
        AppItem("firewall", AppGroupId.NETWORK, null, R.string.firewall_title, "Shield"),
        AppItem("upnp", AppGroupId.NETWORK, "upnpd", R.string.upnp_title, "Router"),
        AppItem("wolultra", AppGroupId.NETWORK, "wolultra", R.string.wolultra_title, "WifiTethering", abbr = "WOL"),
        AppItem("samba4", AppGroupId.STORAGE, "samba4", R.string.samba_title, "Share"),
        AppItem("cifs", AppGroupId.STORAGE, "cifs-mount", R.string.cifs_title, "Save"),
        AppItem("usb-printer", AppGroupId.STORAGE, "usb_printer", R.string.usb_printer_title, "Print"),
        AppItem("autoreboot", AppGroupId.SYSTEM, "autoreboot", R.string.autoreboot_title, "Schedule"),
    )

    /** 全部需要探测的 config 名（并行 uci get 的清单，去重保序） */
    val probeConfigs: List<String> =
        (tools + plugins).mapNotNull { it.probeConfig }.distinct()

    /**
     * 可见项分组（纯函数，单测覆盖）：tools 恒显；插件 fixed（probeConfig=null）恒显，
     * 否则 installed[config]==true 才显；空组不出现。组序按 AppGroupId 声明序。
     */
    fun visibleGroups(installed: Map<String, Boolean>): List<Pair<AppGroupId, List<AppItem>>> =
        AppGroupId.entries.mapNotNull { group ->
            val apps = (tools + plugins)
                .filter { it.group == group }
                .filter { it.probeConfig == null || installed[it.probeConfig] == true }
            if (apps.isEmpty()) null else group to apps
        }
}
