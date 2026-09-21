package dev.wrtctrl.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme

private val AppTypography = Typography(
    displayLarge = TextStyle(fontSize = 57.sp, fontWeight = FontWeight.Normal, lineHeight = 64.sp, letterSpacing = (-0.25).sp),
    displayMedium = TextStyle(fontSize = 45.sp, fontWeight = FontWeight.Normal, lineHeight = 52.sp),
    displaySmall = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Normal, lineHeight = 44.sp),
    headlineLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Normal, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Normal, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Normal, lineHeight = 32.sp),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp, letterSpacing = 0.15.sp),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 24.sp, letterSpacing = 0.5.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp, letterSpacing = 0.25.sp),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp, letterSpacing = 0.5.sp),
)

/**
 * 主题入口：主色/容器/语义色由 materialkolor（Material Color Utilities 音阶体系）从种子色
 * Vibrant 风格生成（简约 + 轻微科技感）；表面族用中性覆盖——彩底廉价，
 * 渐变/科技感只上图表与主色。种子色缺省沿用既有品牌色 #0E84B5。
 * 对比度回归门见 ThemePaletteTest。
 */
@Composable
fun WrtTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    seedColor: Color? = null,
    content: @Composable () -> Unit,
) {
    // 色板 remember（标准做法；根组合重组频率极低，防御性收敛派生成本）
    val colorScheme = remember(darkTheme, seedColor) {
        val seed = seedColor ?: Color(0xFF0E84B5)
        // Vibrant = 提饱和风格；isAmoled 无默认值需显式传
        val vibrant = dynamicColorScheme(
            seedColor = seed,
            isDark = darkTheme,
            isAmoled = false,
            style = PaletteStyle.Vibrant,
        )
        // 中性表面（与 一致）：蓝灰彩底 → 中性灰；文本角色保留 Vibrant（对比度实测达标）
        if (darkTheme) {
            vibrant.copy(
                background = Color(0xFF191C1E),
                surface = Color(0xFF191C1E),
                surfaceContainerLowest = Color(0xFF14171A),
                surfaceContainerLow = Color(0xFF1D2022),
                surfaceContainer = Color(0xFF282A2C),
                surfaceContainerHigh = Color(0xFF333537),
                surfaceContainerHighest = Color(0xFF333537),
                surfaceVariant = Color(0xFF282A2C),
            )
        } else {
            vibrant.copy(
                background = Color(0xFFFBFCFF),
                surface = Color(0xFFFBFCFF),
                surfaceContainerLowest = Color(0xFFFFFFFF),
                surfaceContainerLow = Color(0xFFF3F3F6),
                surfaceContainer = Color(0xFFEDEEF0),
                surfaceContainerHigh = Color(0xFFE2E2E5),
                surfaceContainerHighest = Color(0xFFE2E2E5),
                surfaceVariant = Color(0xFFEDEEF0),
            )
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
