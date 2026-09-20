package dev.wrtctrl.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wrtctrl.ui.theme.ChartColors

/**
 * 进度类可视化：资源环 RingCell（环 + 环下名称/比值两行，阈值转色）
 * 与细进度条 Meter（NAT 会话占用等「值 / 上限」参照物）。首页先行，其他页面复用。
 * 配色走 ChartColors（品牌蓝青 + 警示橙），深浅色 track 自适应。
 */

private const val FONT_FEATURE_TABULAR = "tnum"

/** 环阈值：默认品牌色，>70% 转 warn、>90% 转 err */
private const val RING_WARN_PERCENT = 70
private const val RING_ERR_PERCENT = 90

private enum class RingTier { NORMAL, WARN, ERR }

/** 细进度条：track + 品牌渐变填充（打满即真实故障的参照物） */
@Composable
fun Meter(fraction: Float, modifier: Modifier = Modifier) {
    val track = if (isSystemInDarkTheme()) Color(0xFF2A2C31) else Color(0xFFF1F2F5)
    Canvas(modifier.fillMaxWidth().height(4.dp)) {
        val radius = size.height / 2f
        drawRoundRect(color = track, cornerRadius = CornerRadius(radius, radius))
        val w = size.width * fraction.coerceIn(0f, 1f)
        when {
            w > size.height / 2f -> drawRoundRect(
                brush = Brush.horizontalGradient(listOf(ChartColors.rx, ChartColors.tx)),
                size = Size(w, size.height),
                cornerRadius = CornerRadius(radius, radius),
            )
            w > 0f -> drawCircle(color = ChartColors.tx, radius = radius, center = Offset(radius, radius))
        }
    }
}

/** 资源环单元：环 + 环下名称（粗体）+ 比值明细（内存 99 / 243 MB 式，'/' 只留给比值）。
 *  环为 fillMaxWidth + aspectRatio(1f) 正方形：列宽由 weight 决定，环随列伸缩恒为正圆
 *  （绝对环径的列会被明细文字撑宽/被 Row 挤压——历史椭圆问题根因）。 */
@Composable
fun RingCell(label: String, percent: Int, detail: String?, modifier: Modifier = Modifier) {
    val tier = when {
        percent >= RING_ERR_PERCENT -> RingTier.ERR
        percent >= RING_WARN_PERCENT -> RingTier.WARN
        else -> RingTier.NORMAL
    }
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Ring(percent, Modifier.fillMaxWidth().aspectRatio(1f), tier)
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        if (!detail.isNullOrBlank()) {
            // 10sp 单行：三环等分列宽装不下 12sp 的「396 MB / 940 MB」，必折行（实测发现的问题）；
            // 字号取 10sp（按 390dp 宽度折算）。极窄屏 Ellipsis 兜底不再折行
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 信号档位序号 0–4（差/较弱/一般/良好/极好），与 SignalBars 同阈值（五级阈值） */
fun signalLevel(dBm: Int): Int = when {
    dBm >= -50 -> 4
    dBm >= -60 -> 3
    dBm >= -67 -> 2
    dBm >= -75 -> 1
    else -> 0
}

/** 四格信号条：dBm → 点亮格数，对齐信号档位五级 */
@Composable
fun SignalBars(dBm: Int, modifier: Modifier = Modifier) {
    val on = signalLevel(dBm)
    val onColor = ChartColors.tx
    val offColor = if (isSystemInDarkTheme()) Color(0xFF3A3D44) else Color(0xFFD8DAE0)
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        listOf(4.dp, 6.dp, 8.dp, 10.dp).forEachIndexed { i, h ->
            Box(
                Modifier
                    .padding(start = if (i == 0) 0.dp else 2.dp)
                    .size(3.dp, h)
                    .background(if (i < on) onColor else offColor, RoundedCornerShape(1.dp)),
            )
        }
    }
}

private fun ringBrush(tier: RingTier): Brush = when (tier) {
    RingTier.NORMAL -> Brush.linearGradient(listOf(ChartColors.rx, ChartColors.tx))
    RingTier.WARN -> Brush.linearGradient(ChartColors.warning)
    RingTier.ERR -> Brush.linearGradient(listOf(ChartColors.warning[1], ChartColors.warning[1]))
}

/** 环形进度：渐变弧 + 圆头端帽 + 进度动画；环中心只显百分比（名称/明细在环下两行）。
 *  弧线绘制矩形向内缩半个描边（描边以画布边界为中心线会外溢半宽，是展开时压到卡头的重叠根因）；
 *  并按画布短边画正圆居中——画布一旦被压缩成矩形（Row 宽度不足挤压），圆也不会变椭圆。 */
@Composable
private fun Ring(
    percent: Int,
    modifier: Modifier = Modifier,
    tier: RingTier = RingTier.NORMAL,
) {
    val animated by animateFloatAsState(
        targetValue = percent / 100f,
        animationSpec = tween(600),
        label = "ring",
    )
    val track = if (isSystemInDarkTheme()) Color(0xFF2A2C31) else Color(0xFFF1F2F5)
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
                brush = ringBrush(tier),
                startAngle = -90f,
                sweepAngle = 360f * animated.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = topLeft,
                size = Size(d, d),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Text(
            "${(animated * 100).toInt()}%",
            style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = FONT_FEATURE_TABULAR),
            fontWeight = FontWeight.Bold,
        )
    }
}
