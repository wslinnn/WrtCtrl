package dev.wrtctrl.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerController
import com.patrykandpatrick.vico.compose.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.LineCartesianLayerMarkerTarget
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import dev.wrtctrl.R
import dev.wrtctrl.ui.component.GroupHeader
import dev.wrtctrl.ui.component.KpiGrid
import dev.wrtctrl.ui.component.KpiValue
import dev.wrtctrl.ui.component.PollingGate
import dev.wrtctrl.ui.component.rememberLiveFollowScrollState
import dev.wrtctrl.ui.theme.ChartColors
import dev.wrtctrl.util.Format
import dev.wrtctrl.viewmodel.StatisticsParsers
import dev.wrtctrl.viewmodel.StatisticsUiState
import dev.wrtctrl.viewmodel.StatisticsViewModel
import dev.wrtctrl.viewmodel.WindowStats
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// 底栏 Tab 序号（AppNav TABS 固定顺序）
/** 统计页（两节合一屏，结论先行）：接口 chips → 吞吐卡（峰值大字 +
 *  当前/平均 context + 图表 + 本窗口传输）→ 负载区（当前负载 KPI + 三线图）。
 *  可见时 3s 静默轮询。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(vm: StatisticsViewModel, deviceId: String?, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(deviceId) { vm.ensureLoaded(deviceId) }
    // 可见才轮询：组合级可见（底部 Tab 选中）× 生命周期双门控（共享 PollingGate）
    PollingGate(onActiveChange = vm::setPollingActive)
    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = vm::refresh,
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { GroupHeader(stringResource(R.string.statistics_throughput), Modifier.padding(top = 4.dp)) }
            if (state.interfaces.size > 1) {
                item {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        state.interfaces.forEachIndexed { index, name ->
                            if (index > 0) Spacer(Modifier.width(8.dp))
                            FilterChip(
                                selected = state.selectedDevice == name,
                                onClick = { vm.selectDevice(name) },
                                label = { Text(name) },
                            )
                        }
                    }
                }
            }
            item { ThroughputSection(state) }
            item { GroupHeader(stringResource(R.string.statistics_load), Modifier.padding(top = 4.dp)) }
            item { LoadSection(state) }
        }
    }
}

/** 吞吐卡：↓↑ 峰值大字（当前/平均为 context，单位逐值独立）+ 双面积图 + 本窗口传输积分 */
@Composable
private fun ThroughputSection(state: StatisticsUiState) {
    val dark = isSystemInDarkTheme()
    val rxText = if (dark) ChartColors.rxTextDark else ChartColors.rxTextLight
    val txText = if (dark) ChartColors.txTextDark else ChartColors.txTextLight
    val downlink = stringResource(R.string.home_downlink)
    val uplink = stringResource(R.string.home_uplink)
    val peakLabel = stringResource(R.string.home_peak)
    val currentLabel = stringResource(R.string.statistics_current)
    val averageLabel = stringResource(R.string.statistics_average)
    val rxStats = remember(state.rxSeries) { StatisticsParsers.windowStats(state.rxSeries) }
    val txStats = remember(state.txSeries) { StatisticsParsers.windowStats(state.txSeries) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // ↓↑ 两组整组水平居中
            KpiGrid(horizontalArrangement = Arrangement.spacedBy(56.dp, Alignment.CenterHorizontally)) {
                PeakKpi(
                    label = "$downlink · $peakLabel",
                    stats = rxStats,
                    valueColor = rxText,
                    labelColor = rxText,
                    currentLabel = currentLabel,
                    averageLabel = averageLabel,
                )
                PeakKpi(
                    label = "$uplink · $peakLabel",
                    stats = txStats,
                    valueColor = txText,
                    labelColor = txText,
                    currentLabel = currentLabel,
                    averageLabel = averageLabel,
                )
            }
            if (state.rxSeries.isEmpty()) {
                // 空态占位与图表等高——首拉/真无数据时卡片高度不跳
                Box(Modifier.fillMaxWidth().height(170.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.statistics_loading_bandwidth),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val lines = remember(state.rxSeries, state.txSeries) { listOf(state.rxSeries, state.txSeries) }
                MultiLineChart(
                    lines = lines,
                    lineLabels = listOf(
                        stringResource(R.string.statistics_inbound),
                        stringResource(R.string.statistics_outbound),
                    ),
                    timestamps = state.bwTimestamps,
                    colors = remember { listOf(ChartColors.rx, ChartColors.tx) },
                    areaColors = remember { listOf(ChartColors.rxArea, ChartColors.txArea) },
                    modifier = Modifier.height(170.dp),
                    yFormatter = { Format.rate(it.roundToLong()) },
                )
                WindowTransferRow(state)
            }
        }
    }
}

/** 峰值 KPI：值 = 窗口峰值，context = 当前/平均各占一行，
 *  逐值带单位 */
@Composable
private fun PeakKpi(
    label: String,
    stats: WindowStats?,
    valueColor: Color,
    labelColor: Color,
    currentLabel: String,
    averageLabel: String,
    modifier: Modifier = Modifier,
) {
    val peak = stats?.let { Format.rateParts(it.peak.roundToLong()) }
    val context = stats?.let {
        "$currentLabel ${Format.rate(it.current.roundToLong())}\n$averageLabel ${Format.rate(it.average.roundToLong())}"
    }
    KpiValue(
        label = label,
        value = peak?.value ?: "--",
        unit = peak?.unit,
        context = context,
        valueColor = valueColor,
        labelColor = labelColor,
        modifier = modifier,
    )
}

/** 本窗口传输 = 速率窗口梯形积分（确定性计算，StatisticsParsers.windowTransfer） */
@Composable
private fun WindowTransferRow(state: StatisticsUiState) {
    val dark = isSystemInDarkTheme()
    val rxText = if (dark) ChartColors.rxTextDark else ChartColors.rxTextLight
    val txText = if (dark) ChartColors.txTextDark else ChartColors.txTextLight
    val transfer = remember(state.rxSeries, state.txSeries, state.bwTimestamps) {
        StatisticsParsers.windowTransfer(state.rxSeries, state.bwTimestamps) to
            StatisticsParsers.windowTransfer(state.txSeries, state.bwTimestamps)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.statistics_window_transfer) + " ",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "↓ ${Format.bytes(transfer.first)}",
            style = MaterialTheme.typography.labelMedium,
            color = rxText,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            " · ",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "↑ ${Format.bytes(transfer.second)}",
            style = MaterialTheme.typography.labelMedium,
            color = txText,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 负载区：当前负载 KPI（1min 值 + 5/15min context）+ 三线图 */
@Composable
private fun LoadSection(state: StatisticsUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.loadRows.isEmpty()) {
                // 空态占位与图表等高
                Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.statistics_loading_load),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val label1 = stringResource(R.string.statistics_load_1min)
                val label5 = stringResource(R.string.statistics_load_5min)
                val label15 = stringResource(R.string.statistics_load_15min)
                // 负载三线：1min 主色、5min 主色 55%、15min 灰虚线，无面积填充；
                // 线宽层级 2.5/2/1.5——虚线用默认粗细会压过 1min 主线
                val c1 = MaterialTheme.colorScheme.primary
                val c5 = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                val c15 = MaterialTheme.colorScheme.onSurfaceVariant
                val strokes = remember {
                    listOf(
                        LineCartesianLayer.LineStroke.Continuous(2.5.dp),
                        LineCartesianLayer.LineStroke.Continuous(2.dp),
                        LineCartesianLayer.LineStroke.Dashed(1.5.dp),
                    )
                }
                // 三线窗口统计：KPI 取 1min 当前值，context 带 5/15min 当前值
                val s1 = remember(state.loadRows) { StatisticsParsers.windowStats(state.loadRows.map { it.load1 }) }
                val s5 = remember(state.loadRows) { StatisticsParsers.windowStats(state.loadRows.map { it.load5 }) }
                val s15 = remember(state.loadRows) { StatisticsParsers.windowStats(state.loadRows.map { it.load15 }) }
                KpiValue(
                    label = label1,
                    value = s1?.let { StatisticsParsers.loadText(it.current) } ?: "--",
                    context = s5?.let { v5 ->
                        val v15 = s15?.let { StatisticsParsers.loadText(it.current) } ?: "--"
                        "$label5 ${StatisticsParsers.loadText(v5.current)} · $label15 $v15"
                    },
                )
                LegendRow(listOf(c1 to label1, c5 to label5, c15 to label15))
                // 同吞吐区：由行数据派生的列表 remember，避免每次重组重建实例
                val rows = state.loadRows
                val lines = remember(rows) {
                    listOf(rows.map { it.load1 }, rows.map { it.load5 }, rows.map { it.load15 })
                }
                MultiLineChart(
                    lines = lines,
                    lineLabels = listOf(label1, label5, label15),
                    timestamps = remember(rows) { rows.map { it.ts } },
                    colors = remember(c1, c5, c15) { listOf(c1, c5, c15) },
                    strokes = strokes,
                    modifier = Modifier.height(140.dp),
                    yFormatter = { StatisticsParsers.loadText(it) },
                )
            }
        }
    }
}

/** 自绘图例：短色条 + 标签横排（色板 12×3 色条样式；不引入 Vico legend 组件） */
@Composable
private fun LegendRow(entries: List<Pair<Color, String>>) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        entries.forEachIndexed { index, (color, label) ->
            if (index > 0) Spacer(Modifier.width(12.dp))
            Box(Modifier.size(12.dp, 3.dp).background(color, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 通用多线折线：层配置 remember、数据走 modelProducer、chartTs 与事务同帧对齐（首页崩溃教训）、
 *  轴标签禁空串；点击查值 marker（时间 + 每线标签与值，标签行数 = 线数+1，lineCount 必须显式）。
 *  areaColors 缺省 = 无面积填充（负载三线纯线条）；strokes 逐线指定线型与粗细层级
 *  。底轴保持默认 placer——
 *  segmented 语义是整轴均分铺标签，在不满屏的图上反而铺密全截断。
 *  高度由调用方给定（吞吐 170dp/负载 140dp，按固定设计比例）。 */
@Composable
private fun MultiLineChart(
    lines: List<List<Double>>,
    lineLabels: List<String>,
    timestamps: List<Long>,
    colors: List<Color>,
    yFormatter: (Double) -> String,
    modifier: Modifier = Modifier,
    areaColors: List<Color>? = null,
    strokes: List<LineCartesianLayer.LineStroke>? = null,
) {
    val modelProducer = remember { CartesianChartModelProducer() }
    var chartTs by remember { mutableStateOf<List<Long>>(emptyList()) }
    LaunchedEffect(lines) {
        if (lines.any { it.isEmpty() }) return@LaunchedEffect
        modelProducer.runTransaction {
            lineModel { lines.forEach { s -> series(s) } }
        }
        chartTs = timestamps
    }
    val currentTs by rememberUpdatedState(chartTs)
    val lineProvider = remember(colors, areaColors, strokes) {
        LineCartesianLayer.LineProvider.series(
            colors.mapIndexed { i, color ->
                LineCartesianLayer.Line(
                    fill = LineCartesianLayer.LineFill.single(Fill(color)),
                    stroke = strokes?.getOrNull(i) ?: LineCartesianLayer.LineStroke.Continuous(),
                    // AreaFill.single 不收 null：无面积用全透明填充（负载三线纯线条）
                    areaFill = LineCartesianLayer.AreaFill.single(
                        areaColors?.getOrNull(i)?.let(::Fill) ?: Fill(Color.Transparent),
                    ),
                    interpolator = LineCartesianLayer.Interpolator.cubic(),
                )
            },
        )
    }
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val labels = rememberUpdatedState(lineLabels)
    val marker = rememberDefaultCartesianMarker(
        label = rememberTextComponent(lineCount = lineLabels.size + 1),
        valueFormatter = { _, targets ->
            val lineTarget = targets.filterIsInstance<LineCartesianLayerMarkerTarget>().firstOrNull()
            val idx = lineTarget?.x?.roundToInt()
            buildString {
                append(idx?.let { i -> currentTs.getOrNull(i)?.let { Format.chartTime(it, timeFmt) } } ?: "--")
                lineTarget?.points?.forEachIndexed { i, point ->
                    labels.value.getOrNull(i)?.let { l ->
                        append("\n$l: ").append(yFormatter(point.entry.y))
                    }
                }
            }
        },
        labelPosition = DefaultCartesianMarker.LabelPosition.AroundPoint,
    )
    val bottomFormatter = CartesianValueFormatter { _, value, _ ->
        val idx = value.roundToInt()
        currentTs.getOrNull(idx)?.let { Format.chartTime(it, timeFmt) } ?: "--"
    }
    Column(modifier.fillMaxWidth()) {
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberLineCartesianLayer(lineProvider),
                startAxis = VerticalAxis.rememberStart(
                    horizontalLabelPosition = VerticalAxis.HorizontalLabelPosition.Inside,
                    valueFormatter = { _, value, _ -> yFormatter(value) },
                ),
                bottomAxis = HorizontalAxis.rememberBottom(valueFormatter = bottomFormatter),
                marker = marker,
                markerController = CartesianMarkerController.rememberToggleOnTap(),
            ),
            modelProducer = modelProducer,
            // 默认停在最右跟随新点；用户左滑回看历史即不再被拽回（图表自由滑动）
            scrollState = rememberLiveFollowScrollState(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}
