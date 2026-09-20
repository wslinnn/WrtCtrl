package dev.wrtctrl.ui.screen

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wrtctrl.R
import dev.wrtctrl.ui.component.GroupHeader
import dev.wrtctrl.viewmodel.AppGroupId
import dev.wrtctrl.viewmodel.AppItem
import dev.wrtctrl.viewmodel.AppRegistry
import dev.wrtctrl.viewmodel.AppsViewModel

/**
 * 应用中心（底栏第 5 Tab）：固定工具 + luci 插件分组网格。
 * 插件按 uci get 探测显隐（firewall 恒显）；页面无轮询（进页/下拉刷新/切设备探测一次）。
 * 工具图标进入对应工具页（覆盖本 Tab 内容区）；插件点击 toast「即将推出」。
 * 网格自适应：GridCells.Adaptive(80.dp) 宽屏 5+ 列 / 窄屏 4 列，分组标题占满整行。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(
    vm: AppsViewModel,
    deviceId: String?,
    onSessionLost: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(deviceId) { vm.ensureLoaded(deviceId) }
    val context = LocalContext.current
    val comingSoon = stringResource(R.string.apps_coming_soon)
    // 工具页覆盖：组合级分支，返回键由 ToolPage 自带 BackHandler 兜底，此处仅记 id
    var openToolId by rememberSaveable { mutableStateOf<String?>(null) }
    val toolId = openToolId
    if (toolId != null) {
        // 工具页与外层下拉刷新结构互斥：诊断/重启页无内层刷新，宿主 PTR 不得越权重探测插件
        ToolRouter(
            toolId = toolId,
            deviceId = deviceId,
            onBack = { openToolId = null },
            onSessionLost = onSessionLost,
        )
    } else {
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
                    // groups 恒非空（固定工具 probeConfig=null 恒显），无空态分支
                    val groups = remember(state.installed) { AppRegistry.visibleGroups(state.installed) }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 80.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        groups.forEach { (group, apps) ->
                            item(key = "header_${group.name}", span = { GridItemSpan(maxLineSpan) }) {
                                GroupHeader(
                                    stringResource(group.titleRes),
                                    Modifier.padding(top = 12.dp, bottom = 4.dp),
                                )
                            }
                            items(
                                count = apps.size,
                                key = { i -> "${group.name}_${apps[i].id}" },
                            ) { i ->
                                AppGridItem(
                                    app = apps[i],
                                    onClick = {
                                        if (isToolId(apps[i].id)) {
                                            openToolId = apps[i].id
                                        } else {
                                            Toast.makeText(context, comingSoon, Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppGridItem(app: AppItem, onClick: () -> Unit) {
    val icon = remember(app.icon) { resolveIcon(app.icon) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.size(50.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (app.abbr != null) {
                    Text(
                        app.abbr,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(app.nameRes),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
