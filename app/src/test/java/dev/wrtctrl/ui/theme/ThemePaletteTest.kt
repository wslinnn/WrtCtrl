package dev.wrtctrl.ui.theme

import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * 主题色板守卫：materialkolor Vibrant 方案的关键文本角色
 * 在卡片底（surfaceContainerLow）上的对比度必须 ≥4.5:1（WCAG AA 正文）——
 * 换种子色/风格/库版本时此测试即回归门。同时 println 全表供色板 token 对齐。
 */
class ThemePaletteTest {

    private fun Color.hex(): String {
        val argb = (alpha * 255).toInt().shl(24) or
            (red * 255).toInt().shl(16) or
            (green * 255).toInt().shl(8) or
            (blue * 255).toInt()
        return "#%06X".format(argb and 0xFFFFFF)
    }

    private fun Color.luminance(): Double {
        fun ch(c: Float): Double {
            val v = c.toDouble()
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(red) + 0.7152 * ch(green) + 0.0722 * ch(blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance()
        val lb = b.luminance()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test
    fun `vibrant 色板关键文本角色对比度达 AA`() {
        for (isDark in listOf(false, true)) {
            val scheme = dynamicColorScheme(
                seedColor = Color(0xFF0E84B5),
                isDark = isDark,
                isAmoled = false,
                style = PaletteStyle.Vibrant,
            )
            val card = scheme.surfaceContainerLow
            val roles = listOf(
                "onSurface" to scheme.onSurface,
                "onSurfaceVariant" to scheme.onSurfaceVariant,
                "primary" to scheme.primary,
            )
            println("=== Vibrant ${if (isDark) "dark" else "light"} ===")
            println("surface              = ${scheme.surface.hex()}")
            println("surfaceContainerLow  = ${scheme.surfaceContainerLow.hex()}")
            println("surfaceContainer     = ${scheme.surfaceContainer.hex()}")
            println("surfaceContainerHigh = ${scheme.surfaceContainerHighest.hex()}")
            println("outlineVariant       = ${scheme.outlineVariant.hex()}")
            println("card(surfaceContainerLow) = ${card.hex()}")
            for ((name, color) in roles) {
                val ratio = contrast(color, card)
                println("${name.padEnd(18)} ${color.hex()}  ${"%.2f".format(ratio)}:1")
                assertTrue(
                    "$name ${color.hex()} on card ${card.hex()} = ${"%.2f".format(ratio)}:1 < 4.5",
                    ratio >= 4.5,
                )
            }
        }
    }
}
