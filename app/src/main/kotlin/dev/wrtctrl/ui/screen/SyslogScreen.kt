package dev.wrtctrl.ui.screen

import android.app.Application
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.wrtctrl.R
import dev.wrtctrl.ui.component.PollingGate
import dev.wrtctrl.viewmodel.LogLineUi
import dev.wrtctrl.viewmodel.SyslogViewModel

/**
 * 系统日志页：syslog/dmesg 双源分段 + 级别过滤 + 关键字 +
 * 自动刷新开关+ 手动刷新钮；行级着色；计数 x/y；新数据滚底；全部复制。
 */
@Composable
fun SyslogScreen(deviceId: String?, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: SyslogViewModel = viewModel(factory = viewModelFactory { initializer { SyslogViewModel(app) } })
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(deviceId) { vm.ensureLoaded(deviceId) }
    PollingGate(onActiveChange = vm::setVisible)
    var level by rememberSaveable { mutableStateOf("all") }
    var keyword by rememberSaveable { mutableStateOf("") }

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // 过滤结果 remember（5s 轮询每次重组都重算）
    val filtered = remember(state.lines, level, keyword) {
        state.lines
            .filter { level == "all" || it.level == level }
            .filterMatching(keyword) { listOf(it.text) }
    }

    ToolPage(
        title = stringResource(R.string.syslog_title),
        onBack = onBack,
        refreshing = state.refreshing,
        onRefresh = vm::refresh,
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(8.dp))
            // 源分段：syslog / dmesg（切源清列表重拉，VM 承担）
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = state.source == "syslog",
                    onClick = { vm.setSource("syslog") },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text(stringResource(R.string.syslog_source_syslog)) }
                SegmentedButton(
                    selected = state.source == "dmesg",
                    onClick = { vm.setSource("dmesg") },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text(stringResource(R.string.syslog_source_dmesg)) }
            }
            Spacer(Modifier.height(8.dp))
            // 级别分段：全部/错误/警告/信息
            val levels = listOf(
                "all" to R.string.syslog_level_all,
                "err" to R.string.syslog_level_err,
                "warn" to R.string.syslog_level_warn,
                "info" to R.string.syslog_level_info,
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                levels.forEachIndexed { index, (value, res) ->
                    SegmentedButton(
                        selected = level == value,
                        onClick = { level = value },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = levels.size),
                    ) { Text(stringResource(res)) }
                }
            }
            Spacer(Modifier.height(8.dp))
            ToolSearchField(keyword, stringResource(R.string.syslog_search)) { keyword = it }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AutoRefreshRow(state.autoRefresh, vm::setAutoRefresh)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = vm::refresh, enabled = !state.refreshing) {
                    Text(stringResource(R.string.syslog_refresh))
                }
            }
            when {
                state.loading -> ToolStateBox(stringResource(R.string.syslog_loading), spinner = true)
                filtered.isEmpty() && state.loadFailed -> ToolStateBox(toolLoadFailedText())
                filtered.isEmpty() -> ToolStateBox(stringResource(R.string.syslog_empty))
                else -> {
                    // 计数「x / y」+ 滚底 + 全部复制
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${filtered.size} / ${state.lines.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(filtered.joinToString("\n") { it.text }))
                            Toast.makeText(context, context.getString(R.string.syslog_copied), Toast.LENGTH_SHORT).show()
                        }) { Text(stringResource(R.string.syslog_copy)) }
                    }
                    // weight(1f) 撑满剩余高度（固定 480dp 在矮屏/横屏溢出）
                    LogList(filtered, Modifier.weight(1f))
                }
            }
        }
    }
}

/** 日志列表：monospace + 级别着色；数据更新自动滚底。
 *  高度由调用方给定（weight 撑满剩余空间），不再固定 480dp。 */
@Composable
private fun LogList(lines: List<LogLineUi>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }
    Card(modifier.fillMaxWidth()) {
        LazyColumn(
            Modifier.fillMaxSize().padding(8.dp),
            state = listState,
        ) {
            items(lines.size) { i ->
                val line = lines[i]
                MonoText(
                    line.text,
                    Modifier.fillMaxWidth(),
                    color = when (line.level) {
                        "err" -> MaterialTheme.colorScheme.error
                        "warn" -> LogWarnColor
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

// 警示琥珀：与设备列表 ping 中档同色（浅深底均可读）
private val LogWarnColor = androidx.compose.ui.graphics.Color(0xFFB26A00)
