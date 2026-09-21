package dev.wrtctrl.ui.screen

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import dev.wrtctrl.ui.component.PollingGate
import dev.wrtctrl.util.Format
import dev.wrtctrl.viewmodel.ConnRow
import dev.wrtctrl.viewmodel.ConntrackViewModel
import dev.wrtctrl.viewmodel.WindowStats

/** 活动连接页：统计卡 + 搜索 + 双开关（自动刷新/DNS 反查）+ 连接列表 */
@Composable
fun ConntrackScreen(deviceId: String?, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: ConntrackViewModel = viewModel(factory = viewModelFactory { initializer { ConntrackViewModel(app) } })
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(deviceId) { vm.ensureLoaded(deviceId) }
    PollingGate(onActiveChange = vm::setVisible)
    var keyword by rememberSaveable { mutableStateOf("") }

    ToolPage(
        title = stringResource(R.string.conntrack_title),
        onBack = onBack,
        refreshing = state.refreshing,
        onRefresh = vm::refresh,
    ) {
        Column(Modifier.fillMaxSize()) {
            if (state.udp != null && state.tcp != null && state.other != null) {
                StatsCard(state.udp!!, state.tcp!!, state.other!!)
                Spacer(Modifier.height(12.dp))
            }
            ToolCard {
                ToolSearchField(keyword, stringResource(R.string.conntrack_search)) { keyword = it }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = state.autoRefresh, onCheckedChange = vm::setAutoRefresh)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.conntrack_auto_refresh),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(16.dp))
                    Switch(checked = state.dnsEnabled, onCheckedChange = vm::setDnsEnabled)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.conntrack_dns),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when {
                state.loading -> ToolStateBox(spinner = true)
                else -> {
                    // 过滤结果 remember（5s 轮询每次重组都重算）
                    val rows = remember(state.rows, keyword) {
                        state.rows.filterMatching(keyword) {
                            listOf(it.protocol, it.network, it.src, it.dst, it.sport?.toString(), it.dport?.toString())
                        }
                    }
                    when {
                        rows.isEmpty() && state.loadFailed -> ToolStateBox(toolLoadFailedText())
                        rows.isEmpty() -> ToolStateBox(stringResource(R.string.conntrack_empty))
                        else -> Column(Modifier.fillMaxSize()) {
                            Text(
                                stringResource(R.string.conntrack_total, state.total) + " · " +
                                    "${rows.size} / ${state.rows.size}",
                                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            LazyColumn(
                                Modifier.fillMaxSize(),
                                contentPadding = ToolListPadding,
                                verticalArrangement = ToolItemSpacing,
                            ) {
                                // 五元组 key：列表按字节数降序重排，位置 key 会全行错位重组
                                items(rows.size, key = { i ->
                                    val r = rows[i]
                                    "${r.protocol}-${r.src}-${r.sport}-${r.dst}-${r.dport}"
                                }) { i ->
                                    ConnRowCard(rows[i], state.dnsCache)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 统计卡：UDP(品牌)/TCP(健康)/其它(警示) × 当前/平均/峰值 */
@Composable
private fun StatsCard(udp: WindowStats, tcp: WindowStats, other: WindowStats) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            val current = stringResource(R.string.conntrack_current)
            val average = stringResource(R.string.conntrack_average)
            val peak = stringResource(R.string.conntrack_peak)
            StatsRow("UDP", udp, MaterialTheme.colorScheme.primary, listOf(current, average, peak))
            StatsRow("TCP", tcp, MaterialTheme.colorScheme.tertiary, listOf(current, average, peak))
            StatsRow(
                stringResource(R.string.conntrack_stat_other),
                other,
                MaterialTheme.colorScheme.error,
                listOf(current, average, peak),
            )
        }
    }
}

@Composable
private fun StatsRow(label: String, stats: WindowStats, color: androidx.compose.ui.graphics.Color, caps: List<String>) {
    val values = listOf(stats.current, stats.average, stats.peak).map { it.toLong().toString() }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            Modifier.width(64.dp),
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.Bold,
        )
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
            values.forEachIndexed { i, v ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        v,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        caps[i],
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnRowCard(row: ConnRow, dnsCache: Map<String, String>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f)) {
                    ToolBadge(
                        row.protocol.ifBlank { "-" },
                        when (row.protocol) {
                            "TCP" -> ToolBadgeKind.Positive
                            "UDP" -> ToolBadgeKind.Info
                            else -> ToolBadgeKind.Neutral
                        },
                    )
                    if (row.network == "IPV6") {
                        Spacer(Modifier.width(4.dp))
                        ToolBadge("IPV6", ToolBadgeKind.Neutral)
                    }
                }
                Text(
                    Format.bytes(row.bytes),
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(6.dp))
            val src = endpoint(row.src, row.sport, dnsCache)
            val dst = endpoint(row.dst, row.dport, dnsCache)
            MonoText("$src → $dst", Modifier.fillMaxWidth())
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.conntrack_packets, row.packets),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 端点：反查命中显示主机名，IPv6 地址加方括号（旧 endpoint 同款） */
private fun endpoint(ip: String, port: Int?, dnsCache: Map<String, String>): String {
    if (ip.isBlank()) return "-"
    val name = dnsCache[ip] ?: ip
    val addr = if (name.contains(':')) "[$name]" else name
    return if (port != null) "$addr:$port" else addr
}
