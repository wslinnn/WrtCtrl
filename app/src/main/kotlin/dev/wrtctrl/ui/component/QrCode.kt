package dev.wrtctrl.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import dev.wrtctrl.util.WifiQr

/**
 * 二维码：WifiQr.qrModules 生成模块矩阵后 Canvas 自绘——
 * 白底深码、静区由 WifiQr 的 MARGIN hint + 容器 padding 承担。内容为空/编码失败时
 * 不绘制任何模块（空态守卫由调用方处理）。
 */
@Composable
fun QrCodeImage(
    content: String,
    modifier: Modifier = Modifier,
    dark: Color = Color.Black,
    light: Color = Color.White,
) {
    val modules = remember(content) { WifiQr.qrModules(content) }
    Canvas(modifier.aspectRatio(1f)) {
        drawRect(light)
        val m = modules ?: return@Canvas
        val n = m.size
        val cell = size.width / n
        for (y in 0 until n) {
            val row = m[y]
            for (x in 0 until n) {
                if (row[x]) {
                    drawRect(
                        dark,
                        topLeft = Offset(x * cell, y * cell),
                        size = Size(cell, cell),
                    )
                }
            }
        }
    }
}
