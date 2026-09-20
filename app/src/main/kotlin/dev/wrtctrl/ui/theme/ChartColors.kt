package dev.wrtctrl.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 数据可视化固定色板（品牌蓝青，沿用既有图表配色）：入站/出站双色在首页带宽图、
 * 统计页带宽图、用量条、资源环之间共用——同类数据一套色语言，不随种子色变化，
 * 深浅色模式下均可读。
 */
object ChartColors {
    val rx = Color(0xFF4FACFE)
    val tx = Color(0xFF00C4CC)

    // 面积填充用纯色半透明（约 18%）：shader 渐变在 3s 高频刷新下会出现渲染斑点，纯色走 Paint 直绘
    val rxArea = Color(0x2E4FACFE)
    val txArea = Color(0x2800C4CC)

    /** 语义警示渐变（环组）：CPU/内存/温度越限时的橙→深橙，替代品牌蓝青传达健康信号 */
    val warning = listOf(Color(0xFFFFB300), Color(0xFFF4511E))
}
