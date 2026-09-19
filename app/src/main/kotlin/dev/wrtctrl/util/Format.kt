package dev.wrtctrl.util

import java.util.Locale
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import org.json.JSONArray

/** 数值/时长/带宽格式化 + 采样差分 */
object Format {

    // 数值格式固定 Locale：避免系统语言为逗号小数区时输出「1,2 KB」类歧义文本（zh/en 均为点号，行为不变）
    private val NUM = Locale.US

    /** 1024 进制：B 整数 / KB 1 位小数 / MB·GB 2 位 */
    fun bytes(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> String.format(NUM, "%.1f KB", b / 1024.0)
        b < 1024L * 1024 * 1024 -> String.format(NUM, "%.2f MB", b / 1024.0 / 1024)
        else -> String.format(NUM, "%.2f GB", b / 1024.0 / 1024 / 1024)
    }

    /** 速率自适应单位：B/s 整数 / KB/s 整数 / MB·GB 1 位小数 */
    fun rate(value: Long): String {
        if (value <= 0) return "0 B/s"
        val units = listOf("B/s", "KB/s", "MB/s", "GB/s")
        var i = floor(ln(value.toDouble()) / ln(1024.0)).toInt()
        if (i >= units.size) i = units.size - 1
        if (i <= 0) return "$value B/s"
        val n = value / 1024.0.pow(i)
        return if (i == 1) "${n.roundToInt()} ${units[i]}" else String.format(NUM, "%.1f %s", n, units[i])
    }

    fun duration(seconds: Long): String {
        val d = seconds / 86400
        val h = (seconds % 86400) / 3600
        val m = (seconds % 3600) / 60
        val sec = seconds % 60
        var str = ""
        if (d > 0) str += "${d}d "
        if (h > 0 || d > 0) str += "${h}h "
        if (m > 0 || h > 0 || d > 0) str += "${m}m "
        str += "${sec}s"
        return str
    }

    /** 紧凑计数（1.2k 风格、k≥100 取整、.0 尾去零；无 M 档） */
    fun compactCount(n: Long): String {
        if (n < 1000) return n.toString()
        val k = n / 1000.0
        val num = if (k >= 100) {
            String.format(NUM, "%.0f", k)
        } else {
            String.format(NUM, "%.1f", k)
        }
        return (if (num.endsWith(".0")) num.dropLast(2) else num) + "k"
    }

    /** 无线码率：iwinfo.bitrate 单位 kbit/s → Mbit/s 保留 1 位 */
    fun bitrate(kbitPerSec: Double): String =
        String.format(NUM, "%.1f Mbit/s", kbitPerSec / 1000.0)

    data class BandwidthSeries(
        val timestamps: MutableList<Long> = mutableListOf(),
        val rx: MutableList<Double> = mutableListOf(),
        val tx: MutableList<Double> = mutableListOf(),
    )

    /**
     * 带宽采样差分（clamp=true）：
     * samples: [[ts, rx, ?, tx],...]；dt<=0 跳过；负值（计数器回绕）置 0
     */
    fun bandwidthRates(samples: JSONArray, clamp: Boolean = true): BandwidthSeries {
        val series = BandwidthSeries()
        if (samples.length() < 2) return series
        fun row(i: Int): List<Double> {
            val item = samples.getJSONArray(i)
            return (0 until item.length()).map { item.optDouble(it, 0.0) }
        }
        for (i in 1 until samples.length()) {
            val cur = row(i)
            val prev = row(i - 1)
            if (cur.size < 4 || prev.size < 4) continue
            val dt = cur[0] - prev[0]
            if (dt <= 0) continue
            var rx = (cur[1] - prev[1]) / dt
            var tx = (cur[3] - prev[3]) / dt
            if (clamp) {
                rx = rx.coerceAtLeast(0.0)
                tx = tx.coerceAtLeast(0.0)
            }
            series.timestamps += cur[0].roundToLong()
            series.rx += rx
            series.tx += tx
        }
        return series
    }
}
