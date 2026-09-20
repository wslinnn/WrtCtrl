package dev.wrtctrl.ui.screen

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.wrtctrl.R
import dev.wrtctrl.ui.component.CopyableRow
import dev.wrtctrl.viewmodel.RouteRow
import dev.wrtctrl.viewmodel.RouteViewModel
import dev.wrtctrl.viewmodel.StartupRow
import dev.wrtctrl.viewmodel.StartupViewModel

/** 路由表页：#序号 + 家族·类型徽章；六行全 copyable */
@Composable
fun RouteScreen(deviceId: String?, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: RouteViewModel = viewModel(factory = viewModelFactory { initializer { RouteViewModel(app) } })
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(deviceId) { vm.ensureLoaded(deviceId) }
    var search by rememberSaveable { mutableStateOf("") }
    ToolPage(
        title = stringResource(R.string.route_title),
        onBack = onBack,
        refreshing = state.refreshing,
        onRefresh = vm::refresh,
    ) {
        Column(Modifier.fillMaxSize()) {
            ToolCard {
                ToolSearchField(search, stringResource(R.string.syslog_search)) { search = it }
            }
            Spacer(Modifier.height(12.dp))
            when {
                state.loading -> ToolStateBox(spinner = true)
                state.rows.isEmpty() && state.errorRes != null ->
                    ToolErrorRetry(stringResource(state.errorRes!!), onRetry = vm::load)
                state.rows.isEmpty() -> ToolStateBox(stringResource(R.string.route_no_routes))
                else -> {
                    // 六字段客户端过滤（目的地/网关/设备/src/scope/table）
                    val rows = state.rows.filterMatching(search) {
                        listOf(it.destination, it.gateway, it.device, it.src, it.scope, it.table)
                    }
                    if (rows.isEmpty()) {
                        ToolStateBox(stringResource(R.string.client_no_match))
                    } else {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = ToolListPadding,
                            verticalArrangement = ToolItemSpacing,
                        ) {
                            items(rows.size) { i -> RouteCard(i, rows[i]) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteCard(index: Int, route: RouteRow) {
    val familyLabel = if (route.family == "ipv6") "IPv6" else "IPv4"
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "#${index + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(start = 8.dp))
                ToolBadge("$familyLabel · " + stringResource(route.typeRes), ToolBadgeKind.Info)
            }
            route.destination?.let { CopyableRow(stringResource(R.string.route_destination), it) }
            route.gateway?.let { CopyableRow(stringResource(R.string.route_gateway), it) }
            route.device?.let { CopyableRow(stringResource(R.string.route_device), it) }
            route.src?.let { CopyableRow(stringResource(R.string.route_src), it) }
            route.scope?.let { CopyableRow(stringResource(R.string.route_scope), it) }
            route.table?.let { CopyableRow(stringResource(R.string.route_table), it) }
        }
    }
}

/** 启动项页：单卡行式；运行中(正)/已停止(警示)/已禁用(中性) */
@Composable
fun StartupScreen(deviceId: String?, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: StartupViewModel = viewModel(factory = viewModelFactory { initializer { StartupViewModel(app) } })
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(deviceId) { vm.ensureLoaded(deviceId) }
    var search by rememberSaveable { mutableStateOf("") }
    ToolPage(
        title = stringResource(R.string.startup_title),
        onBack = onBack,
        refreshing = state.refreshing,
        onRefresh = vm::refresh,
    ) {
        Column(Modifier.fillMaxSize()) {
            ToolCard {
                ToolSearchField(search, stringResource(R.string.syslog_search)) { search = it }
            }
            Spacer(Modifier.height(12.dp))
            when {
                state.loading -> ToolStateBox(spinner = true)
                state.rows.isEmpty() && state.loadFailed -> ToolStateBox(toolLoadFailedText())
                state.rows.isEmpty() -> ToolStateBox(stringResource(R.string.startup_no_startup))
                else -> {
                    val rows = state.rows.filterMatching(search) { listOf(it.name) }
                    if (rows.isEmpty()) {
                        ToolStateBox(stringResource(R.string.client_no_match))
                    } else {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = ToolListPadding,
                        ) {
                            item {
                                Card(Modifier.fillMaxWidth()) {
                                    Column {
                                        rows.forEachIndexed { index, row ->
                                            StartupRowItem(row)
                                            if (index != rows.lastIndex) {
                                                HorizontalDivider(Modifier.padding(horizontal = 12.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StartupRowItem(row: StartupRow) {
    val kind = when {
        row.running -> ToolBadgeKind.Positive
        row.enabled -> ToolBadgeKind.Warning
        else -> ToolBadgeKind.Neutral
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.startup_priority) + " ${row.start}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ToolBadge(stringResource(row.statusRes), kind)
    }
}
