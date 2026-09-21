package dev.wrtctrl.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 全局状态徽章：配色取语义色 token（ok 绿/warn 琥珀/err 红浅深各一档），
 * 不随主题种子色漂移——状态色是语义不是装饰。词表见 BadgeTone。
 * 底色 = 同系双色斜向淡渐变。
 */

/** 色板 token：浅色主题用深前景保证对比度，深色主题用亮前景（fg, 无 bg——容器由 fg 派生） */
@Composable
private fun toneForeground(tone: BadgeTone, dark: Boolean): Color = when (tone) {
    BadgeTone.OK -> if (dark) Color(0xFF53C988) else Color(0xFF1E7E46)
    BadgeTone.WARN -> if (dark) Color(0xFFFFB74D) else Color(0xFF8A6508)
    BadgeTone.ERR -> if (dark) Color(0xFFFF8A65) else Color(0xFFC43A0F)
    BadgeTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** 语义色前景（供非徽章场景复用同一套状态色，如客户端汇总卡计数） */
@Composable
fun badgeToneColor(tone: BadgeTone): Color = toneForeground(tone, isSystemInDarkTheme())

@Composable
fun StatusBadge(
    text: String,
    tone: BadgeTone,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    dot: Boolean = true,
) {
    val dark = isSystemInDarkTheme()
    val fg = toneForeground(tone, dark)
    // 中性 chip 走 surfaceVariant 实底（SolidColor 零渐变开销）；语义色容器 = 同系双色斜向淡渐变
    val container = if (tone == BadgeTone.NEUTRAL) {
        SolidColor(MaterialTheme.colorScheme.surfaceVariant)
    } else if (dark) {
        Brush.linearGradient(listOf(fg.copy(alpha = 0.20f), fg.copy(alpha = 0.06f)))
    } else {
        Brush.linearGradient(listOf(fg.copy(alpha = 0.16f), fg.copy(alpha = 0.08f)))
    }
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (dot) {
                Spacer(
                    Modifier
                        .size(6.dp)
                        .background(fg, CircleShape),
                )
            }
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = if (tone == BadgeTone.NEUTRAL) MaterialTheme.colorScheme.onSurfaceVariant else fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
