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
    /** 能力副标题（并3：插件瓦片/工具行的能力一句话；null 不显） */
    @StringRes val descRes: Int? = null,
)

object AppRegistry {
    /** 维护工具 */
    val tools = listOf(
        AppItem("diag", AppGroupId.TOOLS, null, R.string.apps_diag, "MonitorHeart", descRes = R.string.desc_diag),
        AppItem("conntrack", AppGroupId.TOOLS, null, R.string.apps_conntrack, "Link", descRes = R.string.desc_conntrack),
        AppItem("syslog", AppGroupId.TOOLS, null, R.string.apps_syslog, "Description", descRes = R.string.desc_syslog),
        AppItem("process", AppGroupId.TOOLS, null, R.string.apps_process, "Memory", descRes = R.string.desc_process),
        AppItem("route", AppGroupId.TOOLS, null, R.string.apps_route, "Route", descRes = R.string.desc_route),
        AppItem("startup", AppGroupId.TOOLS, null, R.string.apps_startup, "Power", descRes = R.string.desc_startup),
        AppItem("reboot", AppGroupId.TOOLS, null, R.string.apps_reboot, "RestartAlt"),
    )

    val plugins = listOf(
        AppItem("arpbind", AppGroupId.NETWORK, "arpbind", R.string.arpbind_title, "PushPin", descRes = R.string.desc_arpbind),
        AppItem("firewall", AppGroupId.NETWORK, null, R.string.firewall_title, "Shield", descRes = R.string.desc_firewall),
        AppItem("upnp", AppGroupId.NETWORK, "upnpd", R.string.upnp_title, "Router", descRes = R.string.desc_upnp),
        AppItem(
            "wolultra", AppGroupId.NETWORK, "wolultra", R.string.wolultra_title,
            "WifiTethering", abbr = "WOL", descRes = R.string.desc_wolultra,
        ),
        AppItem("samba4", AppGroupId.STORAGE, "samba4", R.string.samba_title, "Share", descRes = R.string.desc_samba),
        AppItem("cifs", AppGroupId.STORAGE, "cifs-mount", R.string.cifs_title, "Save", descRes = R.string.desc_cifs),
        AppItem("usb-printer", AppGroupId.STORAGE, "usb_printer", R.string.usb_printer_title, "Print", descRes = R.string.desc_usb_printer),
        AppItem("autoreboot", AppGroupId.SYSTEM, "autoreboot", R.string.autoreboot_title, "Schedule", descRes = R.string.desc_autoreboot),
    )

    /** 探测用 config 名全集（并行 uci get 的清单，去重保序） */
    val probeConfigs: List<String> =
        (tools + plugins).mapNotNull { it.probeConfig }.distinct()

    /** 可见性：插件 fixed（probeConfig=null）恒显，否则 installed[config]==true 才显；
     *  tools 恒显（唯一实现，单测直接覆盖生产路径） */
    private fun visible(installed: Map<String, Boolean>, items: List<AppItem>) =
        items.filter { it.probeConfig == null || installed[it.probeConfig] == true }

    /**
     * 页面两段结构：第一段 = 全部 luci 插件（跳外部网页，组内原序 NETWORK→
     * STORAGE→SYSTEM），第二段 = 全部原生工具（app 内屏）——四分组降为 AppGroupId 内部
     * 归类依据，不再各自出 sechead。
     */
    fun visibleSections(installed: Map<String, Boolean>): Pair<List<AppItem>, List<AppItem>> =
        visible(installed, plugins) to visible(installed, tools)
}
