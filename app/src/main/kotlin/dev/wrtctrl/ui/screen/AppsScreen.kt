package dev.wrtctrl.ui.screen

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.remember
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
import dev.wrtctrl.viewmodel.AppDisplay
import dev.wrtctrl.viewmodel.AppGroupId
import dev.wrtctrl.viewmodel.AppRegistry
import dev.wrtctrl.viewmodel.AppsViewModel

/**
 * 应用中心（底栏第 5 Tab）：固定工具 + luci 插件分组网格。
 * 插件按 uci get 探测显隐（firewall 恒显）；页面无轮询（进页/下拉刷新/切设备探测一次）。
 * 插件页本体在 M4 落地，工具页在 落地——此前点击 toast「即将推出」。
 * 网格自适应：GridCells.Adaptive(80.dp) 宽屏 5+ 列 / 窄屏 4 列，分组标题占满整行。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(vm: AppsViewModel, deviceId: String?, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(deviceId) { vm.ensureLoaded(deviceId) }
    val context = LocalContext.current
    val comingSoon = stringResource(R.string.apps_coming_soon)
    val currentComingSoon by androidx.compose.runtime.rememberUpdatedState(comingSoon)
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
                val groups = remember(state.installed) { AppRegistry.visibleGroups(state.installed) }
                if (groups.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.apps_no_plugins),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 80.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        groups.forEach { (group, apps) ->
                            item(key = "header_${group.name}", span = { GridItemSpan(maxLineSpan) }) {
                                GroupHeader(group)
                            }
                            items(
                                count = apps.size,
                                key = { i -> "${group.name}_${apps[i].entry.id}" },
                            ) { i ->
                                AppGridItem(
                                    app = apps[i],
                                    onClick = {
                                        Toast.makeText(context, currentComingSoon, Toast.LENGTH_SHORT).show()
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

/** 分组标题：品牌色竖条 + 标题（与网络页 GroupHeader 同款视觉） */
@Composable
private fun GroupHeader(group: AppGroupId) {
    Row(Modifier.padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(4.dp)
                .height(18.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(8.dp))
        Text(stringResource(group.titleRes), style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun AppGridItem(app: AppDisplay, onClick: () -> Unit) {
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
            appName(app),
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
    "HardDrive" -> Icons.Filled.Save
    "Print" -> Icons.Filled.Print
    "Schedule" -> Icons.Filled.Schedule
    else -> Icons.Filled.Link
}

@Composable
private fun appName(app: AppDisplay): String = when (app.entry.id) {
    "route" -> stringResource(R.string.apps_route)
    "process" -> stringResource(R.string.apps_process)
    "startup" -> stringResource(R.string.apps_startup)
    "diag" -> stringResource(R.string.apps_diag)
    "syslog" -> stringResource(R.string.apps_syslog)
    "conntrack" -> stringResource(R.string.apps_conntrack)
    "reboot" -> stringResource(R.string.apps_reboot)
    "arpbind" -> stringResource(R.string.arpbind_title)
    "firewall" -> stringResource(R.string.firewall_title)
    "upnp" -> stringResource(R.string.upnp_title)
    "wolultra" -> stringResource(R.string.wolultra_title)
    "samba4" -> stringResource(R.string.samba_title)
    "cifs" -> stringResource(R.string.cifs_title)
    "usb-printer" -> stringResource(R.string.usb_printer_title)
    "autoreboot" -> stringResource(R.string.autoreboot_title)
    else -> app.entry.id
}
