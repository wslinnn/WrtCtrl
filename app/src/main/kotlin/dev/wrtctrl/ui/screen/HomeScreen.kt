package dev.wrtctrl.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wrtctrl.R
import dev.wrtctrl.util.Format
import dev.wrtctrl.viewmodel.HomeViewModel

private const val RX_COLOR = 0xFF4FACFE
private const val TX_COLOR = 0xFF00C4CC

/** 首页仪表盘：系统状态 / 内存 / 实时带宽 / 网络状态 / 存储 */
@Composable
fun HomeScreen(vm: HomeViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    if (state.loading) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text(
                    stringResource(R.string.home_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DashboardCard(title = stringResource(R.string.home_system_status)) {
            InfoRow(stringResource(R.string.home_model), state.model)
            InfoRow(stringResource(R.string.home_system_name), state.hostname)
            InfoRow(stringResource(R.string.home_version_info), state.version)
            InfoRow(stringResource(R.string.home_uptime), state.uptime)
            InfoRow(stringResource(R.string.home_cpu_load), state.load)
            InfoRow(stringResource(R.string.home_temperature), state.temperature)
        }

        DashboardCard(title = stringResource(R.string.home_memory_usage)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Ring(percent = state.memoryPercent, modifier = Modifier.size(96.dp))
                Spacer(Modifier.size(16.dp))
                Column {
                    Text("${state.memoryPercent}%", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        state.memoryDetail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        DashboardCard(title = stringResource(R.string.home_resource_monitor)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                RateBlock(label = "↓ RX", rate = state.rxRate, color = Color(RX_COLOR))
                RateBlock(label = "↑ TX", rate = state.txRate, color = Color(TX_COLOR))
            }
            Sparkline(
                rx = state.rxSeries,
                tx = state.txSeries,
                modifier = Modifier.fillMaxWidth().height(120.dp).padding(top = 8.dp),
            )
        }

        DashboardCard(title = stringResource(R.string.home_network_status)) {
            InfoRow(stringResource(R.string.home_wan_ip), state.wanIp)
            InfoRow(stringResource(R.string.home_lan_ip), state.lanIp)
            InfoRow(stringResource(R.string.home_gateway), state.gateway)
            InfoRow(stringResource(R.string.home_dns), state.dns)
            InfoRow(stringResource(R.string.home_connections), state.connections)
        }

        DashboardCard(title = stringResource(R.string.home_disk_status)) {
            if (state.mounts.isEmpty()) {
                Text(
                    "--",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.mounts.forEach { mount ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${mount.mount} (${mount.device})", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "${mount.usagePercent}% · ${mount.detail}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { mount.usagePercent / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DashboardCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

@Composable
private fun RateBlock(label: String, rate: Long, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
        Text(Format.rate(rate), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

/** 内存环形图（M3 主色弧线，参数化单份） */
@Composable
private fun Ring(percent: Int, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val progress = MaterialTheme.colorScheme.primary
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 12.dp.toPx()
            drawArc(
                color = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = progress,
                startAngle = -90f,
                sweepAngle = 360f * percent.coerceIn(0, 100) / 100f,
                useCenter = false,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Text("${percent}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

/** 实时带宽双折线（rx/tx），数据点由 3s 轮询差分产出 */
@Composable
private fun Sparkline(rx: List<Double>, tx: List<Double>, modifier: Modifier = Modifier) {
    val rxColor = Color(RX_COLOR)
    val txColor = Color(TX_COLOR)
    val grid = MaterialTheme.colorScheme.surfaceVariant
    val max = ((rx.maxOrNull() ?: 0.0).coerceAtLeast((tx.maxOrNull() ?: 0.0)) * 1.1).coerceAtLeast(1.0)
    Canvas(modifier) {
        // 横向网格线 5 等分
        for (i in 1..4) {
            val y = size.height * i / 5
            drawLine(color = grid, start = Offset(0f, y), end = Offset(size.width, y), strokeWidth = 1f)
        }
        fun seriesPath(series: List<Double>): androidx.compose.ui.graphics.Path? {
            if (series.size < 2) return null
            val stepX = size.width / (series.size - 1)
            val path = androidx.compose.ui.graphics.Path()
            series.forEachIndexed { index, value ->
                val x = index * stepX
                val y = size.height * (1f - (value / max).toFloat().coerceIn(0f, 1f))
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            return path
        }
        seriesPath(rx)?.let { drawPath(it, rxColor, style = Stroke(4f, cap = StrokeCap.Round)) }
        seriesPath(tx)?.let { drawPath(it, txColor, style = Stroke(4f, cap = StrokeCap.Round)) }
    }
}
