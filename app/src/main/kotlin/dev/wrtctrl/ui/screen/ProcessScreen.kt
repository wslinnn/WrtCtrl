package dev.wrtctrl.ui.screen

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.wrtctrl.R
import dev.wrtctrl.ui.component.CopyableRow
import dev.wrtctrl.ui.component.InfoRow
import dev.wrtctrl.ui.component.PollingGate
import dev.wrtctrl.viewmodel.ProcessRow
import dev.wrtctrl.viewmodel.ProcessViewModel

/**
 * 系统进程页：%CPU 降序；卡头名称+PID+STAT 徽章，
 * CPU(红)/内存(绿) 双列；点卡展开详情；5s 可选轮询，展开态跨刷新按 PID 保留。
 */
@Composable
fun ProcessScreen(deviceId: String?, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: ProcessViewModel = viewModel(factory = viewModelFactory { initializer { ProcessViewModel(app) } })
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(deviceId) { vm.ensureLoaded(deviceId) }
    // 可见 × 自动刷新双门控；autoRefresh 事实源在 VM UiState
    PollingGate(onActiveChange = vm::setVisible)
    var search by rememberSaveable { mutableStateOf("") }

    ToolPage(
        title = stringResource(R.string.process_title),
        onBack = onBack,
        refreshing = state.refreshing,
        onRefresh = vm::refresh,
    ) {
        Column(Modifier.fillMaxSize()) {
            ToolCard {
                ToolSearchField(search, stringResource(R.string.conntrack_search)) { search = it }
                Spacer(Modifier.height(8.dp))
                AutoRefreshRow(state.autoRefresh, vm::setAutoRefresh)
            }
            when {
                state.loading -> ToolStateBox(
                    stringResource(R.string.process_loading_processes),
                    spinner = true,
                )
                else -> {
                    val rows = state.rows.filterMatching(search) {
                        listOf(it.name, it.pid, it.user, it.command)
                    }
                    when {
                        rows.isEmpty() && state.loadFailed -> ToolStateBox(toolLoadFailedText())
                        rows.isEmpty() -> ToolStateBox(stringResource(R.string.process_no_processes))
                        else -> LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = ToolListPadding,
                            verticalArrangement = ToolItemSpacing,
                        ) {
                            items(rows.size, key = { i -> rows[i].pid.ifBlank { "idx$i" } }) { i ->
                                ProcessCard(rows[i], expanded = rows[i].pid in state.expanded) {
                                    vm.toggleExpand(rows[i].pid)
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
private fun ProcessCard(row: ProcessRow, expanded: Boolean, onToggle: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(row.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "PID ${row.pid}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ToolBadge(row.stat.ifBlank { "?" }, ToolBadgeKind.Info)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.process_cpu),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // CPU 用警示红、内存用健康色（语义与早期版本一致）
                    Text(
                        row.cpu,
                        style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.process_memory),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        row.memoryText,
                        style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                CopyableRow("PID", row.pid)
                InfoRow(stringResource(R.string.process_user), row.user)
                InfoRow(stringResource(R.string.process_parent_pid), row.ppid)
                InfoRow(stringResource(R.string.process_memory_percent), row.memPercent)
                CopyableRow(stringResource(R.string.process_command), row.command.ifBlank { "-" })
            }
        }
    }
}
