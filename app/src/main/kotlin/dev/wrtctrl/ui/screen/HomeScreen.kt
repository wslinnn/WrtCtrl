package dev.wrtctrl.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.AutoScrollCondition
import com.patrykandpatrick.vico.compose.cartesian.Scroll
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerController
import com.patrykandpatrick.vico.compose.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.LineCartesianLayerMarkerTarget
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import dev.wrtctrl.R
import dev.wrtctrl.data.DashboardCardId
import dev.wrtctrl.util.Format
import dev.wrtctrl.viewmodel.HomeUiState
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

// 语义警示色（环组）：CPU/温度越限时的橙→深橙渐变，替代品牌蓝青渐变以传达健康信号
private val WARNING_COLORS = listOf(Color(0xFFFFB300), Color(0xFFF4511E))

// 越限阈值：CPU 使用率 ≥85%，温度环百分比 ≥80%（即 ≥80℃，映射区间 40–90℃）
private const val WARNING_PERCENT = 85
private const val TEMP_WARNING_PERCENT = 80

/** 等宽数字：速率/百分比高频变化时不因数字宽度抖动带动布局跳动 */
private const val FONT_FEATURE_TABULAR = "tnum"

/** 环组的一条规格：centerText 非空时环中心显示它（如温度 ℃），否则显示百分比；warning=越限警示色 */
private data class RingSpec(
    val label: String,
    val percent: Int,
    val centerText: String?,
    val detail: String?,
    val warning: Boolean = false,
)

/** 首页仪表盘：卡片顺序/显隐由 DashboardPrefs 驱动（编辑页配置），折叠态同库持久化（跨重启记忆）；
 *  下拉刷新立即补一轮拉取（与 3s 自动轮询并存）；轮询随页面可见性启停。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: HomeViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    // 页面不可见（息屏/退后台/切到覆盖页）即停轮询，回到前台立即恢复
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> vm.setPollingActive(true)
                Lifecycle.Event.ON_PAUSE -> vm.setPollingActive(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
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
    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = vm::refresh,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.cardOrder.filter { it in state.cardEnabled }.forEach { cardId ->
                key(cardId) {
                    DashboardCardBody(cardId, state, onToggle = vm::toggleCollapsed)
                }
            }
        }
    }
}

/** 单张仪表盘卡（按 id 分发；显隐与顺序由外层决定，展开态受控于 UiState.collapsed） */
@Composable
private fun DashboardCardBody(
    cardId: DashboardCardId,
    state: HomeUiState,
    onToggle: (DashboardCardId) -> Unit,
) {
    val expanded = cardId !in state.collapsed
    when (cardId) {
        DashboardCardId.RESOURCE -> {
            CollapsibleCard(
                title = stringResource(R.string.home_resource_monitor),
                expanded = expanded,
                onToggle = { onToggle(cardId) },
                summary = stringResource(R.string.home_cpu) + " " + (state.cpuPercent?.let { "$it%" } ?: "--") +
                    " · " + stringResource(R.string.home_memory) + " ${state.memoryPercent}%",
            ) {
                ResourceRings(state)
            }
        }

        DashboardCardId.BANDWIDTH -> CollapsibleCard(
            title = stringResource(R.string.statistics_bandwidth) + " · " + state.bandwidthSource.uppercase(),
            expanded = expanded,
            onToggle = { onToggle(cardId) },
            summary = "↓ ${Format.rate(state.rxRate)} ↑ ${Format.rate(state.txRate)}",
        ) {
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

        DashboardCardId.SYSTEM -> CollapsibleCard(
            title = stringResource(R.string.home_system_status),
            expanded = expanded,
            onToggle = { onToggle(cardId) },
            // 收起即可确认连的是哪台路由器，不必展开
            summary = "${state.hostname} · ${state.model}",
        ) {
            InfoRow(stringResource(R.string.home_model), state.model)
            InfoRow(stringResource(R.string.home_system_name), state.hostname)
            InfoRow(stringResource(R.string.home_version_info), state.version)
            InfoRow(stringResource(R.string.home_architecture), state.architecture)
            InfoRow(stringResource(R.string.home_target_platform), state.target)
            InfoRow(stringResource(R.string.home_uptime), state.uptime)
        }

        DashboardCardId.NETWORK -> CollapsibleCard(
            title = stringResource(R.string.home_network_status),
            expanded = expanded,
            onToggle = { onToggle(cardId) },
            summary = if (state.wanIp == "--") stringResource(R.string.home_no_ipv4) else state.wanIp,
        ) {
            val hasWan = state.wanIp != "--"
            InfoRow(
                stringResource(R.string.home_wan_ip),
                value = if (hasWan) state.wanIp else stringResource(R.string.home_no_ipv4),
                valueColor = if (hasWan) Color.Unspecified else MaterialTheme.colorScheme.error,
            )
            InfoRow(stringResource(R.string.home_lan_ip), state.lanIp)
            InfoRow(stringResource(R.string.home_gateway), state.gateway)
            InfoRow(stringResource(R.string.home_dns), state.dns)
            InfoRow(stringResource(R.string.home_connections), state.connections)
        }

        DashboardCardId.STORAGE -> CollapsibleCard(
            title = stringResource(R.string.home_disk_status),
            expanded = expanded,
            onToggle = { onToggle(cardId) },
            summary = state.mounts.firstOrNull { it.mount == "/overlay" }?.let { "${it.usagePercent}%" } ?: "--",
        ) {
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

/** 可折叠卡：点标题行展开/收起（箭头指示）。展开态受控（UiState.collapsed 持久化），收起时
 *  卡头右侧显示该卡的关键摘要——收起态自身保有信息量，首页不展开也是完整仪表盘。
 *  动画只用 AnimatedVisibility 单一动画源——再叠 animateContentSize 会双重 measure 掉帧（卡顿根因）。 */
@Composable
private fun CollapsibleCard(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    summary: String? = null,
    content: @Composable () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                // onClickLabel 向无障碍服务播报动作（展开/收起），箭头为装饰性图标
                .clickable(
                    onClickLabel = stringResource(if (expanded) R.string.common_collapse else R.string.common_expand),
                    onClick = onToggle,
                )
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            if (!expanded && !summary.isNullOrBlank()) {
                Text(
                    summary,
                    Modifier.padding(start = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                contentDescription = null,
                modifier = Modifier.padding(start = 8.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun InfoRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            color = valueColor,
        )
    }
}

@Composable
private fun RateBlock(label: String, rate: Long, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
        Text(
            Format.rate(rate),
            style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = FONT_FEATURE_TABULAR),
            fontWeight = FontWeight.Bold,
        )
    }
}

/** 资源监控环组：CPU → 内存 → 温度 一行三环。磁盘不入环组：与存储信息卡重复，
 *  由其卡头摘要（overlay %）+ 挂载点明细承担；数据不可得的环整环移除，余环等分聚拢。
 *  自适应，环径 = 列宽 − 2×呼吸边距，随屏幕伸缩；
 *  环间间隙 = 16dp 设计常量，不靠剩余空间施舍。 */
@Composable
private fun ResourceRings(state: HomeUiState) {
    val specs = buildList {
        state.cpuPercent?.let {
            add(RingSpec(stringResource(R.string.home_cpu), it, "$it%", state.load, it >= WARNING_PERCENT))
        }
        add(
            RingSpec(
                stringResource(R.string.home_memory),
                state.memoryPercent,
                null,
                state.memoryDetail,
                state.memoryPercent >= WARNING_PERCENT,
            ),
        )
        state.tempC?.let { temp ->
            // 环弧映射：40℃=空，90℃=满（路由器结温健康区间），中心显示实际读数
            val percent = (((temp - 40) / 50.0) * 100).roundToInt().coerceIn(0, 100)
            add(RingSpec(stringResource(R.string.home_temperature), percent, "$temp℃", null, percent >= TEMP_WARNING_PERCENT))
        }
    }
    Row(Modifier.fillMaxWidth()) {
        specs.forEach { spec ->
            RingColumn(
                label = spec.label,
                percent = spec.percent,
                centerText = spec.centerText,
                detail = spec.detail,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                warning = spec.warning,
            )
        }
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

/** 环形进度：渐变弧 + 圆头端帽 + 进度动画；环中心「标签 + 读数」（centerText 缺省为百分比）。
 *  弧线绘制矩形向内缩半个描边（描边以画布边界为中心线会外溢半宽，是展开时压到卡头的重叠根因）；
 *  并按画布短边画正圆居中——画布一旦被压缩成矩形（Row 宽度不足挤压），圆也不会变椭圆。 */
@Composable
private fun Ring(
    label: String,
    percent: Int,
    centerText: String? = null,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    warning: Boolean = false,
) {
    val animated by animateFloatAsState(
        targetValue = percent / 100f,
        animationSpec = tween(600),
        label = "ring",
    )
    val track = if (isSystemInDarkTheme()) Color(0xFF2A2C31) else Color(0xFFF1F2F5)
    // 越限（CPU/内存/温度告警阈值）切换橙红警示渐变，传达健康信号；常规为品牌蓝青
    val brush = Brush.linearGradient(if (warning) WARNING_COLORS else listOf(Color(RX_COLOR), Color(TX_COLOR)))
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.11f
            val d = minOf(size.width, size.height) - stroke
            val topLeft = Offset((size.width - d) / 2f, (size.height - d) / 2f)
            drawArc(
                color = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = Size(d, d),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            drawArc(
                brush = brush,
                startAngle = -90f,
                sweepAngle = 360f * animated.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = topLeft,
                size = Size(d, d),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                label,
                style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                centerText ?: "${(animated * 100).toInt()}%",
                style = (if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium)
                    .copy(fontFeatureSettings = FONT_FEATURE_TABULAR),
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** 资源监控环单元：环 + 环下明细（内存=used/total，CPU=负载均值；空明细不占位）。
 *  环为 fillMaxWidth + aspectRatio(1f) 正方形：列宽由 weight 决定，环随列伸缩恒为正圆
 *  （绝对环径的列会被明细文字撑宽/被 Row 挤压——历史椭圆问题根因，。 */
@Composable
private fun RingColumn(
    label: String,
    percent: Int,
    centerText: String?,
    detail: String?,
    modifier: Modifier = Modifier,
    warning: Boolean = false,
) {
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Ring(
            label = label,
            percent = percent,
            centerText = centerText,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            compact = true,
            warning = warning,
        )
        if (!detail.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 实时带宽双折线（Vico 3.3.1）：rx/tx 两条线 + 坐标轴 + 点击查值。
 *  查值用 Vico 原生 marker（ToggleOnTap：点按显示、再点隐藏；guideline 与圆点由库绘制），
 *  命中测试/滚动/轴宽换算全部由库完成——自制浮层的像素反推索引忽略了 Y 轴占宽与滚动偏移，
 *  是「横坐标时间与查值时间不一致」的根因。
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
            lineModel {
                series(rx)
                series(tx)
            }
        }
    }
    // X 轴 formatter 与 marker 标签都经此 State 间接读时间戳：列表每 3s 换新引用，
    // lambda 身份保持稳定，轴/标记不会跟着重建（配置常驻，数据只走 producer）
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
            // 对齐旧 home.vue：平滑曲线 + 半透明面积填充（纯色，见顶部常量注释）。
            // lineProvider 必须 remember：它是静态配置（颜色/线型），内联构造会让 Vico 以其
            // 为 key 的图表层每次重组都重建重绘（轮询期帧尖峰根因）；数据更新只走 modelProducer。
            val lineProvider = remember {
                LineCartesianLayer.LineProvider.series(
                    LineCartesianLayer.Line(
                        fill = LineCartesianLayer.LineFill.single(Fill(Color(RX_COLOR))),
                        areaFill = LineCartesianLayer.AreaFill.single(Fill(Color(RX_AREA))),
                        interpolator = LineCartesianLayer.Interpolator.cubic(),
                    ),
                    LineCartesianLayer.Line(
                        fill = LineCartesianLayer.LineFill.single(Fill(Color(TX_COLOR))),
                        areaFill = LineCartesianLayer.AreaFill.single(Fill(Color(TX_AREA))),
                        interpolator = LineCartesianLayer.Interpolator.cubic(),
                    ),
                )
            }
            val inbound = stringResource(R.string.statistics_inbound)
            val outbound = stringResource(R.string.statistics_outbound)
            val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
            // 查值标记：标签三行 = 时间 / 入站 / 出站（值取自 Vico 命中的采样点，天然对齐）。
            // rememberTextComponent 默认 lineCount=1，多行标签会被截断成「时间..」（v2/v3 同坑）
            val marker = rememberDefaultCartesianMarker(
                label = rememberTextComponent(lineCount = 3),
                valueFormatter = { _, targets ->
                    val lineTarget = targets.filterIsInstance<LineCartesianLayerMarkerTarget>().firstOrNull()
                    val idx = lineTarget?.x?.roundToInt()
                    buildString {
                        append(idx?.let { i -> currentTs.getOrNull(i)?.let(timeFmt::format) } ?: "")
                        lineTarget?.points?.getOrNull(0)?.entry?.y?.let { y ->
                            append("\n$inbound: ").append(Format.rate(y.roundToLong()))
                        }
                        lineTarget?.points?.getOrNull(1)?.entry?.y?.let { y ->
                            append("\n$outbound: ").append(Format.rate(y.roundToLong()))
                        }
                    }
                },
                labelPosition = DefaultCartesianMarker.LabelPosition.AroundPoint,
            )
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
                            val idx = value.roundToInt()
                            currentTs.getOrNull(idx)?.let { timeFmt.format(Date(it * 1000)) } ?: ""
                        },
                    ),
                    marker = marker,
                    markerController = CartesianMarkerController.rememberToggleOnTap(),
                ),
                modelProducer = modelProducer,
                scrollState = scrollState,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
