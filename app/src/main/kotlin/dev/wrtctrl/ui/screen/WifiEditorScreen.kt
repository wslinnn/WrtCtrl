@file:Suppress("TooManyFunctions")

package dev.wrtctrl.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.wrtctrl.R
import dev.wrtctrl.viewmodel.RadioInfo
import dev.wrtctrl.viewmodel.WifiEditorViewModel
import dev.wrtctrl.viewmodel.WifiSchemas

/**
 * wifi 编辑器：
 * target = wireless 的 uci section 名（radio 或 iface，类型由 VM 按段类型分流）。
 * 编辑页复用 schema 驱动的 UciEditPage（高危确认正文换成无线断连/回滚说明）。
 * RadioBar = 无线 Tab 的 radio 组条（启停/重启/详情入口），编辑入口沿用「详情 →」词表。
 */

/** 无线编辑覆盖页（网络页内嵌组合级分支，BackHandler 由 UciEditPage→ToolPage 承担） */
@Composable
fun WifiEditorScreen(
    target: String,
    deviceId: String?,
    onSaved: () -> Unit,
    onBack: () -> Unit,
) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application
    // 单 Activity 作用域复用同一 VM：ensureLoaded 按 deviceId|target 换段重载
    val vm: WifiEditorViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = WifiEditorViewModel(app) as VM
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val candidates by vm.candidates.collectAsStateWithLifecycle()
    val doneEvent by vm.doneEvent.collectAsStateWithLifecycle()
    LaunchedEffect(deviceId, target) { vm.ensureLoaded(deviceId, target) }
    PluginWriteErrorEffect(state.writeErrorRes) { vm.consumeWriteError() }
    PluginDoneEventEffect(doneEvent) { vm.consumeDoneEvent() }
    // 目标段不存在（被删/加载失败/切设备残留）→ 自动回上页（pluginEditorStale 同语义）
    LaunchedEffect(state.loading, state.entry) {
        if (!state.loading && state.entry == null) onBack()
    }
    // wifi-iface 读入归一：encryption 拆出算法后缀进 cipher 草稿（SELECT 只认纯模式）
    val entry = state.entry?.let(WifiSchemas::normalizeEntry)
    if (state.loading || entry == null) {
        ToolPage(title = target, onBack = onBack) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        return
    }
    val specs = remember(entry, state.isRadio, state.mtk, state.band2g) {
        if (state.isRadio) WifiSchemas.radioSchema(state.mtk, state.band2g) else WifiSchemas.ifaceSchema(state.mtk)
    }
    UciEditPage(
        title = entry.first("ssid")?.takeIf { !state.isRadio && it.isNotBlank() } ?: target,
        specs = specs,
        entry = entry,
        candidates = candidates,
        allowDelete = false,
        highRisk = true,
        confirmBodyRes = R.string.wifi_save_confirm,
        saving = state.saving,
        onDelete = null,
        onCancel = onBack,
        onSave = { values ->
            // cipher 拼回 encryption 后缀（uci 无独立 cipher option）
            WifiSchemas.mergeCipherSuffix(values)
            vm.submit(values) { ok ->
                if (ok) {
                    onSaved()
                    onBack()
                }
            }
        },
    )
}

/** 无线 Tab 的 radio 组条：名称 + 频段·芯片·信道·协议摘要 + 重启 + 详情入口 + 启停开关 */
@Composable
internal fun RadioBar(
    radio: RadioInfo,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onRestart: () -> Unit,
    onEdit: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    radio.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = listOfNotNull(
                    radio.band,
                    radio.chip,
                    radio.channel?.let { "Ch $it" },
                    radio.hwmodes?.let { "802.11$it" },
                ).joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onRestart, enabled = !busy) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.wifi_radio_restart))
            }
            TextButton(onClick = onEdit) { Text(stringResource(R.string.entry_details)) }
            if (busy) {
                CircularProgressIndicator(Modifier.size(30.dp).padding(4.dp), strokeWidth = 2.dp)
            } else {
                // disabled=='1' = 关；启停确认弹窗在 Screen 层（高危）
                Switch(checked = !radio.disabled, onCheckedChange = onToggle)
            }
        }
    }
}
