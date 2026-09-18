package dev.wrtctrl.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.core.cartesian.AutoScrollCondition
import com.patrykandpatrick.vico.core.cartesian.Scroll
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.common.Fill
import com.patrykandpatrick.vico.core.common.shader.LinearGradientShaderProvider
import dev.wrtctrl.R
import dev.wrtctrl.util.Format
import dev.wrtctrl.viewmodel.HomeViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val RX_COLOR = 0xFF4FACFE
private const val TX_COLOR = 0xFF00C4CC

// 面积填充用纯色半透明（约 18%）：shader 渐变在 3s 高频刷新下会出现渲染斑点，纯色走 Paint 直绘
private const val RX_AREA = 0x2E4FACFE
private const val TX_AREA = 0x2800C4CC

/** 首页仪表盘：系统状态 / 内存 / 实时带宽 / 网络状态 / 存储 */
@Composable
fun HomeScreen(vm: HomeViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    if (state.loading) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
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
        CollapsibleCard(stringResource(R.string.home_system_status)) {
            InfoRow(stringResource(R.string.home_model), state.model)
            InfoRow(stringResource(R.string.home_system_name), state.hostname)
            InfoRow(stringResource(R.string.home_version_info), state.version)
            InfoRow(stringResource(R.string.home_architecture), state.architecture)
            InfoRow(stringResource(R.string.home_target_platform), state.target)
            InfoRow(stringResource(R.string.home_uptime), state.uptime)
            InfoRow(stringResource(R.string.home_cpu_load), state.load)
            InfoRow(stringResource(R.string.home_temperature), state.temperature)
        }

        CollapsibleCard(stringResource(R.string.home_resource_monitor), initiallyExpanded = true) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                RingColumn(
                    label = stringResource(R.string.home_memory),
                    percent = state.memoryPercent,
                    detail = state.memoryDetail,
                )
                val disk = state.mounts.firstOrNull { it.mount == "/overlay" } ?: state.mounts.firstOrNull()
                RingColumn(
                    label = stringResource(R.string.home_overlay),
                    percent = disk?.usagePercent ?: 0,
                    detail = disk?.detail ?: "--",
                )
            }
        }

        CollapsibleCard(stringResource(R.string.statistics_bandwidth) + " · " + state.bandwidthSource.uppercase(), initiallyExpanded = true) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                RateBlock(label = "↓ RX", rate = state.rxRate, color = Color(RX_COLOR))
                RateBlock(label = "↑ TX", rate = state.txRate, color = Color(TX_COLOR))
            }
            BandwidthChart(
                rx = state.rxSeries,
                tx = state.txSeries,
                timestamps = state.timestamps,
                modifier = Modifier.fillMaxWidth().height(170.dp).padding(top = 8.dp),
            )
        }

        CollapsibleCard(stringResource(R.string.home_network_status)) {
            InfoRow(stringResource(R.string.home_wan_ip), state.wanIp)
            InfoRow(stringResource(R.string.home_lan_ip), state.lanIp)
            InfoRow(stringResource(R.string.home_gateway), state.gateway)
            InfoRow(stringResource(R.string.home_dns), state.dns)
            InfoRow(stringResource(R.string.home_connections), state.connections)
        }

        CollapsibleCard(stringResource(R.string.home_disk_status)) {
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
                    UsageBar(
                        percent = mount.usagePercent,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** 可折叠卡：点标题行展开/收起（箭头指示），展开状态切 Tab 保持；initiallyExpanded 控制默认态。
 *  动画只用 AnimatedVisibility 单一动画源——再叠 animateContentSize 会双重 measure 掉帧（卡顿根因）。 */
@Composable
private fun CollapsibleCard(title: String, initiallyExpanded: Boolean = false, content: @Composable () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (expanded) "▾" else "▸",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(200)) + expandVertically(expandFrom = Alignment.Top),
            exit = fadeOut(tween(150)) + shrinkVertically(shrinkTowards = Alignment.Top),
        ) {
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                content()
            }
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

/** 存储用量条：蓝→青渐变（与图表色系统一）+ 粗圆角条。
 *  不用 M3 LinearProgressIndicator：其新规范默认在轨道末端画 stop indicator 小竖块（即"斑点"），
 *  且 color 参数不支持渐变。 */
@Composable
private fun UsageBar(percent: Int, modifier: Modifier = Modifier) {
    val track = if (isSystemInDarkTheme()) Color(0xFF2A2C31) else Color(0xFFF1F2F5)
    Canvas(modifier.fillMaxWidth().height(16.dp)) {
        val radius = size.height / 2f
        drawRoundRect(color = track, cornerRadius = CornerRadius(radius, radius))
        val w = size.width * (percent / 100f).coerceIn(0f, 1f)
        when {
            w > size.height / 2f -> drawRoundRect(
                brush = Brush.horizontalGradient(listOf(Color(RX_COLOR), Color(TX_COLOR))),
                size = Size(w, size.height),
                cornerRadius = CornerRadius(radius, radius),
            )
            w > 0f -> drawCircle(color = Color(TX_COLOR), radius = radius, center = Offset(radius, radius))
        }
    }
}

/** 环形进度：渐变弧 + 圆头端帽 + 进度动画；环中心「标签 + 百分比」 */
@Composable
private fun Ring(label: String, percent: Int, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(
        targetValue = percent / 100f,
        animationSpec = tween(600),
        label = "ring",
    )
    val track = if (isSystemInDarkTheme()) Color(0xFF2A2C31) else Color(0xFFF1F2F5)
    val brush = Brush.linearGradient(listOf(Color(RX_COLOR), Color(TX_COLOR)))
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
                brush = brush,
                startAngle = -90f,
                sweepAngle = 360f * animated.coerceIn(0f, 1f),
                useCenter = false,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${(animated * 100).toInt()}%",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** 资源监控双环单元：环 + 环下用量明细（used / total） */
@Composable
private fun RingColumn(label: String, percent: Int, detail: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Ring(label, percent, Modifier.size(104.dp))
        Spacer(Modifier.height(12.dp))
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 实时带宽双折线（Vico 2.1）：rx/tx 两条线 + 坐标轴。
 *  查值不用 Vico 触摸 marker（抬手即隐），自制点击常显浮层：tap 换算最近采样点，
 *  竖直参考线 + 顶部时间/入站/出站浮层；再点切换，点同一点取消。
 *  空序列守卫：Vico 对空 series 直接 require 崩溃（闪退根因），首份差分产出前显示占位。 */
@Composable
private fun BandwidthChart(
    rx: List<Double>,
    tx: List<Double>,
    timestamps: List<Long>,
    modifier: Modifier = Modifier,
) {
    val modelProducer = remember { CartesianChartModelProducer() }
    LaunchedEffect(rx, tx) {
        if (rx.isEmpty() || tx.isEmpty()) return@LaunchedEffect
        modelProducer.runTransaction {
            lineSeries {
                series(rx)
                series(tx)
            }
        }
    }
    var selectedIndex by remember { mutableIntStateOf(-1) }
    val currentRx by rememberUpdatedState(rx)
    val currentTx by rememberUpdatedState(tx)
    val currentTs by rememberUpdatedState(timestamps)
    Box(modifier) {
        if (rx.isEmpty()) {
            Text(
                stringResource(R.string.home_chart_collecting),
                Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // 对齐旧 home.vue：平滑曲线 + 半透明面积填充（纯色，见顶部常量注释）
            val lineProvider = LineCartesianLayer.LineProvider.series(
                LineCartesianLayer.Line(
                    fill = LineCartesianLayer.LineFill.single(Fill(Color(RX_COLOR).toArgb())),
                    areaFill = LineCartesianLayer.AreaFill.single(Fill(RX_AREA)),
                    pointConnector = LineCartesianLayer.PointConnector.cubic(),
                ),
                LineCartesianLayer.Line(
                    fill = LineCartesianLayer.LineFill.single(Fill(Color(TX_COLOR).toArgb())),
                    areaFill = LineCartesianLayer.AreaFill.single(Fill(TX_AREA)),
                    pointConnector = LineCartesianLayer.PointConnector.cubic(),
                ),
            )
            val inbound = stringResource(R.string.statistics_inbound)
            val outbound = stringResource(R.string.statistics_outbound)
            val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
            // 默认视口停在最右（最新数据），新点到来自动跟随（OnModelGrowth）
            val scrollState = rememberVicoScrollState(
                initialScroll = Scroll.Absolute.End,
                autoScrollCondition = AutoScrollCondition.OnModelGrowth,
            )
            CartesianChartHost(
                chart = rememberCartesianChart(
                    rememberLineCartesianLayer(lineProvider),
                    startAxis = VerticalAxis.rememberStart(
                        horizontalLabelPosition = VerticalAxis.HorizontalLabelPosition.Inside,
                        valueFormatter = { _, value, _ -> Format.rate(value.roundToLong()) },
                    ),
                    bottomAxis = HorizontalAxis.rememberBottom(
                        valueFormatter = { _, value, _ ->
                            val idx = value.roundToInt().coerceIn(0, timestamps.lastIndex)
                            timestamps.getOrNull(idx)?.let { timeFmt.format(Date(it * 1000)) } ?: ""
                        },
                    ),
                ),
                modelProducer = modelProducer,
                scrollState = scrollState,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val list = currentRx
                            if (list.isEmpty()) return@detectTapGestures
                            val idx = (offset.x / size.width * (list.size - 1)).roundToInt().coerceIn(0, list.size - 1)
                            selectedIndex = if (selectedIndex == idx) -1 else idx
                        }
                    },
            )
            val idx = selectedIndex
            if (idx in currentRx.indices) {
                // 竖直参考线（底部让出 X 轴标签区）
                Canvas(Modifier.matchParentSize()) {
                    val span = (currentRx.size - 1).coerceAtLeast(1)
                    val x = size.width * idx / span
                    drawLine(
                        color = Color(0x66888888),
                        start = Offset(x, 0f),
                        end = Offset(x, size.height - 24.dp.toPx()),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
                val time = currentTs.getOrNull(idx)?.let { timeFmt.format(Date(it * 1000)) } ?: ""
                Column(
                    Modifier
                        .align(Alignment.TopCenter)
                        .background(Color(0xCC202124), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    if (time.isNotBlank()) {
                        Text(time, color = Color(0xFF9CA3AF), fontSize = 10.sp)
                    }
                    Text(
                        "${inbound}: ${Format.rate(currentRx[idx].roundToLong())}",
                        color = Color(RX_COLOR),
                        fontSize = 11.sp,
                    )
                    Text(
                        "${outbound}: ${Format.rate(currentTx[idx].roundToLong())}",
                        color = Color(TX_COLOR),
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}
