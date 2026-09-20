package dev.wrtctrl.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
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
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import dev.wrtctrl.R
import dev.wrtctrl.data.DashboardCardId
import dev.wrtctrl.ui.component.BadgeTone
import dev.wrtctrl.ui.component.EntryLink
import dev.wrtctrl.ui.component.KpiGrid
import dev.wrtctrl.ui.component.KpiValue
import dev.wrtctrl.ui.component.Meter
import dev.wrtctrl.ui.component.PollingGate
import dev.wrtctrl.ui.component.RingCell
import dev.wrtctrl.ui.component.StatusBadge
import dev.wrtctrl.ui.component.rememberLiveFollowScrollState
import dev.wrtctrl.ui.theme.ChartColors
import dev.wrtctrl.util.Format
import dev.wrtctrl.viewmodel.HomeUiState
import dev.wrtctrl.viewmodel.HomeViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// 图表配色统一走 ChartColors（品牌蓝青 + 警示橙）；进度类组件见 ui/component/Gauges

/** 温度徽章警示阈值（℃）：警示阈值 80℃ */
private const val TEMP_WARN_C = 80

// 底栏 Tab 序号（AppNav TABS 固定顺序：首页0/统计1/客户端2/网络3/应用4）；工具 id 对应 AppRegistry.tools
private const val TAB_STATISTICS = 1
private const val TAB_CLIENTS = 2
private const val TAB_NETWORK = 3
private const val TOOL_CONNTRACK = "conntrack"

/** 等宽数字：速率/百分比高频变化时不因数字宽度抖动带动布局跳动 */
private const val FONT_FEATURE_TABULAR = "tnum"

/** 首页：身份区固定 + 三张可折叠卡（吞吐/资源/网络，顺序显隐由 DashboardPrefs 驱动）。
 *  下拉刷新立即补一轮拉取（与 3s 自动轮询并存）；轮询随页面可见性启停。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    modifier: Modifier = Modifier,
    onGotoTab: (Int) -> Unit = {},
    onOpenTool: (String) -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    // 页面不可见（息屏/退后台/切到其他底部 Tab/覆盖页）即停轮询，回到前台立即恢复
    PollingGate(onActiveChange = vm::setPollingActive)
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Hero(state)
            state.cardOrder.filter { it in state.cardEnabled }.forEach { cardId ->
                key(cardId) {
                    DashboardCardBody(
                        cardId = cardId,
                        state = state,
                        onToggle = vm::toggleCollapsed,
                        onGotoTab = onGotoTab,
                        onOpenTool = onOpenTool,
                    )
                }
            }
        }
    }
}

/** 身份区：左列 = 路由名 + 型号行成组，右列 = 状态徽章纵排（延迟在上、温度在下
 *  ，两列顶对齐、行距同为 6dp。
 *  徽章列贴内容右缘：左列必须 weight(1f) 填满——曾用 weight(1f, fill = false) + 占位 Spacer
 *  平分宽度，徽章列悬在行中、行尾留大空白（与折叠摘要截断同族）。
 *  设备切换收敛到顶栏入口。
 *   */
@Composable
private fun Hero(state: HomeUiState) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    state.hostname,
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    Modifier.padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // weight(1f, fill = false)：型号过长时据此收窄省略，保证固件 chip 可见
                    Text(
                        state.model,
                        Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (state.releaseVersion != "--") {
                        Spacer(Modifier.width(6.dp))
                        FirmwareChip(state.releaseVersion)
                    }
                }
                // 「已运行」收进左列：行距对齐左列 2dp 节奏（挂在外部会被更高的徽章列推远）
                Text(
                    stringResource(R.string.home_uptime_prefix, state.uptime),
                    Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 温度 ≥80℃ 转警示，拉不到不占位（数据有源）——阈值语义同旧资源卡标题
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PingBadge(state.pingMs, state.pingOffline)
                state.tempC?.let { temp ->
                    StatusBadge(
                        "$temp℃",
                        if (temp >= TEMP_WARN_C) BadgeTone.WARN else BadgeTone.NEUTRAL,
                        dot = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun PingBadge(pingMs: Long?, offline: Boolean) {
    when {
        pingMs != null -> StatusBadge(stringResource(R.string.home_ping_online, pingMs), BadgeTone.OK)
        offline -> StatusBadge(stringResource(R.string.home_ping_offline), BadgeTone.ERR)
        // 未测得（慢拍未跑/失败前）不占位——不画占位是数据有源纪律
    }
}

@Composable
private fun FirmwareChip(version: String) {
    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Text(
            version,
            Modifier.padding(horizontal = 7.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 单张仪表盘卡分发（显隐与顺序由外层决定）：三卡各自成组组件，避免分发器膨胀 */
@Composable
private fun DashboardCardBody(
    cardId: DashboardCardId,
    state: HomeUiState,
    onToggle: (DashboardCardId) -> Unit,
    onGotoTab: (Int) -> Unit,
    onOpenTool: (String) -> Unit,
) {
    val expanded = cardId !in state.collapsed
    when (cardId) {
        DashboardCardId.BANDWIDTH -> ThroughputCard(state, expanded) { onToggle(cardId) }
        DashboardCardId.RESOURCE -> ResourceCard(state, expanded, onToggle = { onToggle(cardId) }, onGotoTab = onGotoTab)
        DashboardCardId.NETWORK -> NetworkCard(
            state,
            expanded,
            onToggle = { onToggle(cardId) },
            onGotoTab = onGotoTab,
            onOpenTool = onOpenTool,
        )
    }
}

/** 实时吞吐卡（第一卡）：↓↑ 当前值大字（单位独立换档）+ 峰值 context + 双面积折线图 */
@Composable
private fun ThroughputCard(state: HomeUiState, expanded: Boolean, onToggle: () -> Unit) {
    val rxParts = Format.rateParts(state.rxRate)
    val txParts = Format.rateParts(state.txRate)
    val dark = isSystemInDarkTheme()
    val rxText = if (dark) ChartColors.rxTextDark else ChartColors.rxTextLight
    val txText = if (dark) ChartColors.txTextDark else ChartColors.txTextLight
    val downlink = stringResource(R.string.home_downlink)
    val uplink = stringResource(R.string.home_uplink)
    val peakLabel = stringResource(R.string.home_peak)
    // 峰值 = 既有滚动窗口内样本最大值（确定性计算，数据有源）
    val rxPeak = state.rxSeries.maxOrNull()?.roundToLong()?.let(Format::rateParts)
    val txPeak = state.txSeries.maxOrNull()?.roundToLong()?.let(Format::rateParts)
    CollapsibleCard(
        title = stringResource(R.string.home_realtime_throughput) + " · " + state.bandwidthSource.uppercase(),
        expanded = expanded,
        onToggle = onToggle,
        summary = "↓ ${Format.rate(state.rxRate)} ↑ ${Format.rate(state.txRate)}",
    ) {
        // ↓↑ 两组整组水平居中
        KpiGrid(horizontalArrangement = Arrangement.spacedBy(48.dp, Alignment.CenterHorizontally)) {
            KpiValue(
                label = downlink,
                value = rxParts.value,
                unit = rxParts.unit,
                context = rxPeak?.let { "$peakLabel ${it.value} ${it.unit}" },
                valueColor = rxText,
                labelColor = rxText,
                valueSize = 28.sp,
            )
            KpiValue(
                label = uplink,
                value = txParts.value,
                unit = txParts.unit,
                context = txPeak?.let { "$peakLabel ${it.value} ${it.unit}" },
                valueColor = txText,
                labelColor = txText,
                valueSize = 28.sp,
            )
        }
        BandwidthChart(
            rx = state.rxSeries,
            tx = state.txSeries,
            timestamps = state.timestamps,
            modifier = Modifier.fillMaxWidth().height(170.dp).padding(top = 8.dp),
        )
    }
}

/** 资源卡（第二卡）：标题行只留「趋势 →」（温度已迁身份区右列）；
 *  三环（CPU/内存/磁盘）阈值转色，环下名称+比值两行。 */
@Composable
private fun ResourceCard(
    state: HomeUiState,
    expanded: Boolean,
    onToggle: () -> Unit,
    onGotoTab: (Int) -> Unit,
) {
    val cpuPct = state.cpuPercent?.let { "$it%" } ?: "--"
    val disk = state.mounts.firstOrNull { it.mount == "/overlay" }
        ?: state.mounts.firstOrNull()
    CollapsibleCard(
        title = stringResource(R.string.home_resource_monitor),
        expanded = expanded,
        onToggle = onToggle,
        // 折叠摘要三环齐备（与展开态口径一致）；独立文案键：英文 RAM 缩写压宽，
        // Memory 全拼在窄屏/大字体下必截断
        summary = stringResource(
            R.string.home_resource_summary,
            cpuPct,
            "${state.memoryPercent}%",
            disk?.let { "${it.usagePercent}%" } ?: "--",
        ),
        titleExtra = {
            EntryLink(stringResource(R.string.entry_trend)) { onGotoTab(TAB_STATISTICS) }
        },
    ) {
        Row(Modifier.fillMaxWidth()) {
            // 数据不可得的环整环跳过（空态守卫），余环 weight 等分聚拢
            state.cpuPercent?.let {
                RingCell(stringResource(R.string.home_cpu), it, null, Modifier.weight(1f).padding(horizontal = 6.dp))
            }
            RingCell(
                stringResource(R.string.home_memory),
                state.memoryPercent,
                state.memoryDetail,
                Modifier.weight(1f).padding(horizontal = 6.dp),
            )
            disk?.let {
                RingCell(
                    stringResource(R.string.home_disk),
                    it.usagePercent,
                    it.detail,
                    Modifier.weight(1f).padding(horizontal = 6.dp),
                )
            }
        }
    }
}

/** 网络卡（第三卡）：接口 UP/DOWN chip（DOWN 附诊断入口）+ 网关/DNS + NAT 会话进度 + 客户端结论行 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NetworkCard(
    state: HomeUiState,
    expanded: Boolean,
    onToggle: () -> Unit,
    onGotoTab: (Int) -> Unit,
    onOpenTool: (String) -> Unit,
) {
    val gatewayLabel = stringResource(R.string.home_gateway)
    val dnsLabel = stringResource(R.string.home_dns)
    CollapsibleCard(
        title = stringResource(R.string.home_network_status),
        expanded = expanded,
        onToggle = onToggle,
        summary = if (state.wanIp == "--") stringResource(R.string.home_no_ipv4) else state.wanIp,
        titleExtra = {
            EntryLink(stringResource(R.string.entry_details)) { onGotoTab(TAB_NETWORK) }
        },
    ) {
        if (state.ifaceChips.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.ifaceChips.forEach { chip ->
                    // DOWN 纯状态展示
                    StatusBadge(chip.name, if (chip.up) BadgeTone.OK else BadgeTone.ERR)
                }
            }
        }
        if (state.gateway != "--" || state.dns != "--") {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (state.gateway != "--") {
                    StatusBadge("$gatewayLabel ${state.gateway}", BadgeTone.NEUTRAL, dot = false)
                }
                if (state.dns != "--") {
                    StatusBadge("$dnsLabel ${state.dns}", BadgeTone.NEUTRAL, dot = false)
                }
            }
        }
        val connText = if (state.connCount != null && state.connMax != null) {
            "${state.connCount} / ${state.connMax}"
        } else {
            "--"
        }
        LinkRow(stringResource(R.string.home_connections), connText) {
            onOpenTool(TOOL_CONNTRACK)
        }
        val maxV = state.connMax
        val countV = state.connCount
        if (maxV != null && countV != null && maxV > 0) {
            Meter(countV.toFloat() / maxV, Modifier.padding(top = 2.dp, bottom = 4.dp))
        }
        LinkRow(
            stringResource(R.string.tabbar_client),
            stringResource(
                R.string.home_clients_summary,
                state.assocCount?.toString() ?: "--",
                state.leaseCount?.toString() ?: "--",
            ),
        ) {
            onGotoTab(TAB_CLIENTS)
        }
    }
}

/** 可折叠卡：点标题行展开/收起（箭头指示）。展开态受控（UiState.collapsed 持久化），收起时
 *  卡头右侧显示该卡的关键摘要——收起态自身保有信息量，首页不展开也是完整仪表盘。
 *  titleExtra：标题行右侧的槽（入口词链接），只在展开态出现。
 *  折叠摘要 weight(1f) + 右对齐，独占标题与箭头之间的全部剩余宽度——此前与占位 Spacer
 *  平分宽度、只拿到一半被截成「…」。
 *  动画只用 AnimatedVisibility 单一动画源——再叠 animateContentSize 会双重 measure 掉帧（卡顿根因）。 */
@Composable
private fun CollapsibleCard(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    summary: String? = null,
    titleExtra: (@Composable () -> Unit)? = null,
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
            if (!expanded && !summary.isNullOrBlank()) {
                // 摘要右对齐贴住箭头，可用宽度全给摘要；
                // labelSmall 11sp：英文三项摘要在窄屏/大字体下 12sp 装不下
                Text(
                    summary,
                    Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (expanded) titleExtra?.invoke()
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

/** 可点跳转行（NAT 会话 / 客户端结论）：标签 + 值 + ›，品牌色传达「可点」 */
@Composable
private fun LinkRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = FONT_FEATURE_TABULAR),
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            modifier = Modifier.padding(start = 2.dp).size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

/** 实时吞吐双折线（Vico 3.3.1）：rx/tx 两条线 + 坐标轴 + 点击查值。
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
    // 图表时间戳与模型严格同帧对齐：runTransaction 完成后才更新 chartTs——
    // 若直接读 state.timestamps，切设备的过渡帧会出现"模型 60 点/时间戳短列表"错配，
    // 轴格式化查不到索引返回空串，Vico 3 对空串标签直接抛 IllegalStateException（务必避免回归）
    var chartTs by remember { mutableStateOf<List<Long>>(emptyList()) }
    LaunchedEffect(rx, tx, timestamps) {
        if (rx.isEmpty() || tx.isEmpty()) return@LaunchedEffect
        modelProducer.runTransaction {
            lineModel {
                series(rx)
                series(tx)
            }
        }
        chartTs = timestamps
    }
    // X 轴 formatter 与 marker 标签都经此 State 间接读时间戳：lambda 身份保持稳定
    // （配置常驻，数据只走 producer）。Vico 3 禁止轴标签空串，兜底 "--"。
    val currentTs by rememberUpdatedState(chartTs)
    Box(modifier) {
        if (rx.isEmpty()) {
            Text(
                stringResource(R.string.home_chart_collecting),
                Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // 对齐首页图形态：平滑曲线 + 半透明面积填充（纯色，见 ChartColors 注释）。
            // lineProvider 必须 remember：它是静态配置（颜色/线型），内联构造会让 Vico 以其
            // 为 key 的图表层每次重组都重建重绘（轮询期帧尖峰根因）；数据更新只走 modelProducer。
            val lineProvider = remember {
                LineCartesianLayer.LineProvider.series(
                    LineCartesianLayer.Line(
                        fill = LineCartesianLayer.LineFill.single(Fill(ChartColors.rx)),
                        areaFill = LineCartesianLayer.AreaFill.single(Fill(ChartColors.rxArea)),
                        interpolator = LineCartesianLayer.Interpolator.cubic(),
                    ),
                    LineCartesianLayer.Line(
                        fill = LineCartesianLayer.LineFill.single(Fill(ChartColors.tx)),
                        areaFill = LineCartesianLayer.AreaFill.single(Fill(ChartColors.txArea)),
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
                        append(idx?.let { i -> currentTs.getOrNull(i)?.let { Format.chartTime(it, timeFmt) } } ?: "--")
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
            // 默认停在最右跟随新点；用户左滑回看历史即不再被拽回（图表自由滑动）
            val scrollState = rememberLiveFollowScrollState()
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
                            currentTs.getOrNull(idx)?.let { Format.chartTime(it, timeFmt) } ?: "--"
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
