package dev.wrtctrl.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wrtctrl.R
import dev.wrtctrl.ui.component.GroupHeader
import dev.wrtctrl.viewmodel.AppItem
import dev.wrtctrl.viewmodel.AppRegistry
import dev.wrtctrl.viewmodel.AppsViewModel

/**
 * 应用中心（底栏第 5 Tab；两组结构）：
 * 「插件」grid2（luci 插件，后续进入原生插件页）+「维护工具」单卡行列表（原生工具页）。
 * 插件按 uci get 探测显隐（firewall 恒显）；页面无轮询（进页/下拉刷新/切设备探测一次）。
 * 工具/插件页是组合级覆盖分支（appsHolder 按 key 保留），自带 BackHandler。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(
    vm: AppsViewModel,
    deviceId: String?,
    onSessionLost: () -> Unit,
    modifier: Modifier = Modifier,
    /** 跨 Tab 直达工具（首页网络卡 NAT/诊断入口）：非空且合法时开对应工具页 */
    pendingToolId: String? = null,
    onToolConsumed: () -> Unit = {},
    /** 首页直达打开的工具页因返回退出时回调（撤销整个跳转回来源 Tab）；应用列表打开的不回调 */
    onToolExit: () -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(deviceId) { vm.ensureLoaded(deviceId) }
    // 工具页覆盖：组合级分支，返回键由 ToolPage 自带 BackHandler 兜底，此处仅记 id
    var openToolId by rememberSaveable { mutableStateOf<String?>(null) }
    // 打开来源：首页直达 = true（返回撤销整个跳转，经 onToolExit 弹回来源 Tab）；列表点击 = false（停在本 Tab）
    var toolFromShortcut by rememberSaveable { mutableStateOf(false) }
    // 插件页覆盖：组合级分支，返回键由 PluginPage 自带 BackHandler 兜底，返回停在本 Tab
    var openPluginId by rememberSaveable { mutableStateOf<String?>(null) }
    // 状态保留规范：工具/插件页 ↔ 列表互为覆盖分支，组合销毁会丢
    // LazyVerticalGrid 滚动位置——各分支经 holder 按 key 保存恢复
    val appsHolder = rememberSaveableStateHolder()
    // 首页网络卡直达：消费外部带入的工具 id 后回调清空，避免再组合时重复打开
    LaunchedEffect(pendingToolId) {
        if (pendingToolId != null && isToolId(pendingToolId)) {
            openToolId = pendingToolId
            toolFromShortcut = true
            onToolConsumed()
        }
    }
    val toolId = openToolId
    val pluginId = openPluginId
    when {
        toolId != null ->
            // 工具页与外层下拉刷新结构互斥：诊断/重启页无内层刷新，宿主 PTR 不得越权重探测插件
            appsHolder.SaveableStateProvider("tool_$toolId") {
                ToolRouter(
                    toolId = toolId,
                    deviceId = deviceId,
                    onBack = {
                        openToolId = null
                        if (toolFromShortcut) {
                            toolFromShortcut = false
                            onToolExit()
                        }
                    },
                    onSessionLost = onSessionLost,
                )
            }

        pluginId != null && isPluginId(pluginId) ->
            appsHolder.SaveableStateProvider("plugin_$pluginId") {
                PluginRouter(
                    pluginId = pluginId,
                    deviceId = deviceId,
                    onBack = { openPluginId = null },
                )
            }

        else ->
            appsHolder.SaveableStateProvider("apps_list") {
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = vm::refresh,
                modifier = modifier.fillMaxSize(),
            ) {
                when {
                    state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }

                    else -> {
                        // 两段结构：插件（luci）一个 grid + 维护工具一张单卡——
                        // 四分组保留为 AppGroupId 内部归类，不再各自出 sechead
                        val (plugins, tools) = remember(state.installed) { AppRegistry.visibleSections(state.installed) }
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            item(key = "header_plugins", span = { GridItemSpan(maxLineSpan) }) {
                                GroupHeader(
                                    stringResource(R.string.apps_group_plugins),
                                    Modifier.padding(top = 12.dp, bottom = 4.dp),
                                )
                            }
                            items(count = plugins.size, key = { i -> "plugin_${plugins[i].id}" }) { i ->
                                PluginTile(app = plugins[i], onClick = { openPluginId = plugins[i].id })
                            }
                            item(key = "header_tools", span = { GridItemSpan(maxLineSpan) }) {
                                GroupHeader(
                                    stringResource(R.string.apps_group_tools),
                                    Modifier.padding(top = 12.dp, bottom = 4.dp),
                                )
                            }
                            item(key = "tools_card", span = { GridItemSpan(maxLineSpan) }) {
                                ToolsCard(tools) {
                                    toolFromShortcut = false
                                    openToolId = it
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 插件瓦片：图标在上、名称/副标题单行居中，整卡可点无 chevron——
 *  横排 90dp 文字区装不下 14sp 长名称（换行则瓦片行高不齐），竖排文字区 136dp 实测
 *  最长名称/副标题均单行放下，等高 + 单行 + 不截断并立；极端超长兜底单行省略。
 *  desc 与名称相同不显副标题（纯噪声；数据层已修正存量重复，此为兜底）。未接管的插件点击 toast 占位 */
@Composable
private fun PluginTile(app: AppItem, onClick: () -> Unit) {
    val icon = remember(app.icon) { resolveIcon(app.icon) }
    val name = stringResource(app.nameRes)
    val desc = app.descRes?.let { stringResource(it) }
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIconBadge(icon = icon, abbr = app.abbr)
            Text(
                name,
                Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (desc != null && desc != name) {
                Text(
                    desc,
                    Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 维护工具：单卡行列表 + 行间分隔线 */
@Composable
private fun ToolsCard(tools: List<AppItem>, onClick: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
            tools.forEachIndexed { index, app ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                ToolRow(app, onClick = { onClick(app.id) })
            }
        }
    }
}

/** 维护工具行：32px 中性 chip 底图标 + 名称（自然宽永不截断）+
 *  meta 右对齐单行省略（长 meta 让路）+ ›；重启例外 err 底红字收尾 */
@Composable
private fun ToolRow(app: AppItem, onClick: () -> Unit) {
    val icon = remember(app.icon) { resolveIcon(app.icon) }
    val isReboot = app.id == "reboot"
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolIcon(icon = icon, isReboot = isReboot)
        Text(
            stringResource(app.nameRes),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isReboot) FontWeight.SemiBold else FontWeight.Medium,
            color = if (isReboot) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        app.descRes?.let {
            Text(
                stringResource(it),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } ?: Spacer(Modifier.weight(1f))
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (isReboot) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 工具行图标：32px 中性 chip 底 + 灰图标；重启例外 err 底 */
@Composable
private fun ToolIcon(icon: ImageVector, isReboot: Boolean) {
    Box(
        Modifier
            .size(32.dp)
            .background(
                if (isReboot) {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                RoundedCornerShape(10.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (isReboot) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 插件瓦片图标徽章：品牌底 + 品牌图标，无合适图标的用缩写文字（WOL） */
@Composable
private fun AppIconBadge(icon: ImageVector, abbr: String?) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.size(38.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (abbr != null) {
                Text(
                    abbr,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** 注册表 icon 名 → Material 图标（解析留屏层，AppRegistry 保持无 compose 依赖可单测） */
private fun resolveIcon(name: String): ImageVector = when (name) {
    "Route" -> Icons.Filled.Route
    "Memory" -> Icons.Filled.Memory
    "Power" -> Icons.Filled.Power
    "MonitorHeart" -> Icons.Filled.MonitorHeart
    "Description" -> Icons.Filled.Description
    "Link" -> Icons.Filled.Link
    "RestartAlt" -> Icons.Filled.RestartAlt
    "PushPin" -> Icons.Filled.PushPin
    "Shield" -> Icons.Filled.Shield
    "Router" -> Icons.Filled.Router
    "WifiTethering" -> Icons.Filled.WifiTethering
    "Share" -> Icons.Filled.Share
    "Save" -> Icons.Filled.Save
    "Print" -> Icons.Filled.Print
    "Schedule" -> Icons.Filled.Schedule
    else -> Icons.Filled.Link
}
