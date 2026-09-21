package dev.wrtctrl.ui.screen

import android.app.Application
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.delay
import dev.wrtctrl.R
import dev.wrtctrl.viewmodel.DiagViewModel
import dev.wrtctrl.viewmodel.RebootViewModel

/** 重启等待倒计时总时长（秒）：与 RebootViewModel 的 REBOOT_COUNTDOWN_MS 对齐 */
private const val REBOOT_TOTAL_SEC = 60

/** 诊断页：ping/traceroute/nslookup 分段 + 目标 + 次数/跳数 + 结果 monospace + 复制 */
@Composable
fun DiagScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: DiagViewModel = viewModel(factory = viewModelFactory { initializer { DiagViewModel(app) } })
    val state by vm.state.collectAsStateWithLifecycle()
    var tool by rememberSaveable { mutableStateOf("ping") }
    var host by rememberSaveable { mutableStateOf("") }
    var count by rememberSaveable { mutableStateOf("4") }
    var hops by rememberSaveable { mutableStateOf("15") }

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val canRun = !state.running && host.isNotBlank()

    ToolPage(title = stringResource(R.string.diag_title), onBack = onBack) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            val tools = listOf(
                "ping" to R.string.diag_tool_ping,
                "traceroute" to R.string.diag_tool_traceroute,
                "nslookup" to R.string.diag_tool_nslookup,
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                tools.forEachIndexed { index, (value, res) ->
                    SegmentedButton(
                        selected = tool == value,
                        onClick = {
                            if (tool != value) {
                                tool = value
                                vm.clear()
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = tools.size),
                    ) { Text(stringResource(res)) }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text(stringResource(R.string.diag_target)) },
                placeholder = { Text(stringResource(R.string.diag_target_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (tool == "ping" || tool == "traceroute") {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(if (tool == "ping") R.string.diag_count else R.string.diag_hops),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = if (tool == "ping") count else hops,
                        onValueChange = { v ->
                            val digits = v.filter(Char::isDigit).take(3)
                            if (tool == "ping") count = digits else hops = digits
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(96.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { vm.run(tool, host, count.toIntOrNull() ?: 0, hops.toIntOrNull() ?: 0) },
                    enabled = canRun,
                ) {
                    if (state.running) {
                        CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(if (state.running) R.string.diag_running else R.string.diag_run))
                }
                if (state.output != null && !state.running) {
                    Spacer(Modifier.width(12.dp))
                    TextButton(onClick = vm::clear) { Text(stringResource(R.string.diag_clear)) }
                }
            }
            state.errorRes?.let { res ->
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(res),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            val output = state.output
            if (output != null) {
                Spacer(Modifier.height(12.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.diag_result),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = {
                                clipboard.setText(AnnotatedString(output))
                                Toast.makeText(context, context.getString(R.string.diag_copied), Toast.LENGTH_SHORT).show()
                            }) { Text(stringResource(R.string.diag_copy)) }
                        }
                        Spacer(Modifier.height(4.dp))
                        MonoText(output.ifBlank { stringResource(R.string.diag_empty) })
                    }
                }
            }
        }
    }
}

/** 重启页：确认弹窗 → 执行（断连错误属预期）→ 60s 倒计时 → 回门控列表。
 *  倒计时 deadline 在 VM：旋转/切主题重建据 deadline 续算不重置，
 *  到点回设备列表由 VM 级事件驱动——离开页面计时照走，不再依赖组合存活。 */
@Composable
fun RebootScreen(onBack: () -> Unit, onSessionLost: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: RebootViewModel =
        viewModel(factory = viewModelFactory { initializer { RebootViewModel(app) } })
    val state by vm.state.collectAsStateWithLifecycle()
    var confirming by rememberSaveable { mutableStateOf(false) }
    // 剩余秒数：从 VM 的 deadline 续算（deadline 变更即重启，重算起点）
    val deadline = state.deadlineElapsed
    var remainSec by remember(deadline) { mutableIntStateOf(REBOOT_TOTAL_SEC) }

    LaunchedEffect(deadline) {
        val d = deadline ?: return@LaunchedEffect
        while (true) {
            val remainMs = d - SystemClock.elapsedRealtime()
            remainSec = ((remainMs + 999) / 1000).toInt().coerceAtLeast(0)
            if (remainMs <= 0) break
            delay(1000)
        }
    }
    // 倒计时走完 → 会话已死，回设备列表（VM 级一次性事件）
    LaunchedEffect(Unit) {
        vm.rebootCountdownDone.collect { onSessionLost() }
    }

    ToolPage(title = stringResource(R.string.reboot_title), onBack = onBack) {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.reboot_device_restart),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.reboot_restart_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            if (state.rebooting) {
                Text(
                    stringResource(R.string.reboot_device_restarting),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (REBOOT_TOTAL_SEC - remainSec) / REBOOT_TOTAL_SEC.toFloat() },
                    Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.reboot_please_wait) + " ${remainSec}s",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Button(
                    onClick = { confirming = true },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.reboot_restart_device)) }
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.reboot_confirm_restart)) },
            text = { Text(stringResource(R.string.reboot_confirm_restart_content)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    vm.reboot()
                }) {
                    Text(
                        stringResource(R.string.reboot_confirm_restart_text),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}
