package dev.wrtctrl.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.patrykandpatrick.vico.compose.cartesian.AutoScrollCondition
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.Scroll
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerController
import com.patrykandpatrick.vico.compose.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.LineCartesianLayerMarkerTarget
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import dev.wrtctrl.R
import dev.wrtctrl.ui.component.InfoRow
import dev.wrtctrl.util.Format
import dev.wrtctrl.viewmodel.StatisticsParsers
import dev.wrtctrl.viewmodel.StatisticsUiState
import dev.wrtctrl.viewmodel.StatisticsViewModel
import dev.wrtctrl.viewmodel.WindowStats
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** 统计页：带宽 / 负载双 Tab。可见时 3s 静默轮询。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(vm: StatisticsViewModel, deviceId: String?, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(deviceId) { vm.ensureLoaded(deviceId) }
    var tab by remember { mutableIntStateOf(0) }
    LaunchedEffect(tab) { vm.onTab(tab) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> vm.setPollingActive(true)
                Lifecycle.Event.ON_PAUSE -> vm.setPollingActive(false)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.setPollingActive(false)
        }
    }
    Column(modifier) {
        val tabs = listOf(R.string.statistics_bandwidth, R.string.statistics_load)
        TabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { index, res ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = { Text(stringResource(res)) },
                )
            }
        }
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { vm.refresh(tab) },
            modifier = Modifier.fillMaxSize(),
        ) {
            if (tab == 0) BandwidthTab(state, onSelect = vm::selectDevice) else LoadTab(state)
        }
    }
}

@Composable
private fun BandwidthTab(state: StatisticsUiState, onSelect: (String) -> Unit) {
    if (state.interfaces.isEmpty()) {
        CenterStatus(text = stringResource(R.string.statistics_loading_bandwidth), spinner = true)
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        stringResource(R.string.statistics_select_interface),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        state.interfaces.forEachIndexed { index, name ->
                            if (index > 0) Spacer(Modifier.width(8.dp))
                            FilterChip(
                                selected = state.selectedDevice == name,
                                onClick = { onSelect(name) },
                                label = { Text(name) },
                            )
                        }
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    val inbound = stringResource(R.string.statistics_inbound)
                    val outbound = stringResource(R.string.statistics_outbound)
                    val cRx = MaterialTheme.colorScheme.primary
                    val cTx = MaterialTheme.colorScheme.tertiary
                    LegendRow(listOf(cRx to inbound, cTx to outbound))
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth()) {
                        RateStatColumn(
                            title = inbound,
                            stats = StatisticsParsers.windowStats(state.rxSeries),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        RateStatColumn(
                            title = outbound,
                            stats = StatisticsParsers.windowStats(state.txSeries),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    if (state.rxSeries.isEmpty()) {
                        Text(
                            stringResource(R.string.statistics_loading_bandwidth),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        MultiLineChart(
                            lines = listOf(state.rxSeries, state.txSeries),
                            lineLabels = listOf(inbound, outbound),
                            timestamps = state.bwTimestamps,
                            colors = listOf(cRx, cTx),
                            yFormatter = { Format.rate(it.roundToLong()) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadTab(state: StatisticsUiState) {
    if (state.loadLoading && state.loadRows.isEmpty()) {
        CenterStatus(text = stringResource(R.string.statistics_loading_load), spinner = true)
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    val label1 = stringResource(R.string.statistics_load_1min)
                    val label5 = stringResource(R.string.statistics_load_5min)
                    val label15 = stringResource(R.string.statistics_load_15min)
                    val c1 = MaterialTheme.colorScheme.primary
                    val c5 = MaterialTheme.colorScheme.tertiary
                    val c15 = MaterialTheme.colorScheme.secondary
                    LegendRow(listOf(c1 to label1, c5 to label5, c15 to label15))
                    Spacer(Modifier.height(8.dp))
                    if (state.loadRows.isEmpty()) {
                        Text(
                            stringResource(R.string.statistics_loading_load),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        // 三段统计共用三个窗口统计：列取值维度（当前/平均/峰值），行是 1/5/15 分钟
                        val s1 = StatisticsParsers.windowStats(state.loadRows.map { it.load1 })
                        val s5 = StatisticsParsers.windowStats(state.loadRows.map { it.load5 })
                        val s15 = StatisticsParsers.windowStats(state.loadRows.map { it.load15 })
                        Row(Modifier.fillMaxWidth()) {
                            LoadStatColumn(
                                title = stringResource(R.string.statistics_current_load),
                                pick = { it.current },
                                s1 = s1,
                                s5 = s5,
                                s15 = s15,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(12.dp))
                            LoadStatColumn(
                                title = stringResource(R.string.statistics_average),
                                pick = { it.average },
                                s1 = s1,
                                s5 = s5,
                                s15 = s15,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(12.dp))
                            LoadStatColumn(
                                title = stringResource(R.string.statistics_peak),
                                pick = { it.peak },
                                s1 = s1,
                                s5 = s5,
                                s15 = s15,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        MultiLineChart(
                            lines = listOf(
                                state.loadRows.map { it.load1 },
                                state.loadRows.map { it.load5 },
                                state.loadRows.map { it.load15 },
                            ),
                            lineLabels = listOf(label1, label5, label15),
                            timestamps = state.loadRows.map { it.ts },
                            colors = listOf(c1, c5, c15),
                            yFormatter = { StatisticsParsers.loadText(it) },
                        )
                    }
                }
            }
        }
    }
}

/** 带宽统计列：标题 + 当前/平均/峰值三行（compact 行：标签自适应宽，防数值换行） */
@Composable
private fun RateStatColumn(title: String, stats: WindowStats?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InfoRow(stringResource(R.string.statistics_current), stats?.let { Format.rate(it.current.roundToLong()) } ?: "--", compact = true)
        InfoRow(stringResource(R.string.statistics_average), stats?.let { Format.rate(it.average.roundToLong()) } ?: "--", compact = true)
        InfoRow(stringResource(R.string.statistics_peak), stats?.let { Format.rate(it.peak.roundToLong()) } ?: "--", compact = true)
    }
}

/** 负载统计列：标题 + 1/5/15 分钟三行，值取 pick（当前/平均/峰值） */
@Composable
private fun LoadStatColumn(
    title: String,
    pick: (WindowStats) -> Double,
    s1: WindowStats?,
    s5: WindowStats?,
    s15: WindowStats?,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InfoRow(stringResource(R.string.statistics_load_1min), s1?.let { StatisticsParsers.loadText(pick(it)) } ?: "--", compact = true)
        InfoRow(stringResource(R.string.statistics_load_5min), s5?.let { StatisticsParsers.loadText(pick(it)) } ?: "--", compact = true)
        InfoRow(stringResource(R.string.statistics_load_15min), s15?.let { StatisticsParsers.loadText(pick(it)) } ?: "--", compact = true)
    }
}

/** 自绘图例：色点 + 标签横排（不引入 Vico legend 组件） */
@Composable
private fun LegendRow(entries: List<Pair<Color, String>>) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        entries.forEachIndexed { index, (color, label) ->
            if (index > 0) Spacer(Modifier.width(12.dp))
            Box(Modifier.size(8.dp).background(color, CircleShape))
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
 *  轴标签禁空串；点击查值 marker（时间 + 每线标签与值，标签行数 = 线数+1，lineCount 必须显式） */
@Composable
private fun MultiLineChart(
    lines: List<List<Double>>,
    lineLabels: List<String>,
    timestamps: List<Long>,
    colors: List<Color>,
    yFormatter: (Double) -> String,
    modifier: Modifier = Modifier,
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
    val lineProvider = remember(colors) {
        LineCartesianLayer.LineProvider.series(
            colors.map { color ->
                LineCartesianLayer.Line(
                    fill = LineCartesianLayer.LineFill.single(Fill(color)),
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
    Column(modifier.fillMaxWidth().height(220.dp)) {
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberLineCartesianLayer(lineProvider),
                startAxis = VerticalAxis.rememberStart(
                    horizontalLabelPosition = VerticalAxis.HorizontalLabelPosition.Inside,
                    valueFormatter = { _, value, _ -> yFormatter(value) },
                ),
                bottomAxis = HorizontalAxis.rememberBottom(
                    valueFormatter = { _, value, _ ->
                        val idx = value.roundToInt()
                        currentTs.getOrNull(idx)?.let { Format.chartTime(it, timeFmt) } ?: "--"
                    },
                ),
                marker = marker,
                markerController = CartesianMarkerController.rememberToggleOnTap(),
            ),
            modelProducer = modelProducer,
            scrollState = rememberVicoScrollState(
                initialScroll = Scroll.Absolute.End,
                autoScrollCondition = AutoScrollCondition.OnModelGrowth,
            ),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun CenterStatus(text: String, spinner: Boolean = false) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (spinner) {
            CircularProgressIndicator()
        } else {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
