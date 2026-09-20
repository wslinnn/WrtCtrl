package dev.wrtctrl.viewmodel

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToLong

data class LoadRow(val ts: Long, val load1: Double, val load5: Double, val load15: Double)

/** 双线/三线共用的窗口统计：当前（末值）、窗口均值、窗口峰值 */
data class WindowStats(val current: Double, val average: Double, val peak: Double)

/**
 * 统计页纯解析：接口清单、负载数行（÷100 定点）、
 * 窗口统计；带宽差分复用 Format.bandwidthRates。JVM 单测覆盖。
 */
internal object StatisticsParsers {

    /** 接口选择器清单：滤 lo 与显式 down；br-lan 置顶，其余按名排序 */
    fun interfaceOptions(devices: JSONObject): List<String> {
        val root = devices.optJSONObject("result") ?: devices
        val names = mutableListOf<String>()
        for (key in root.keys()) {
            val dev = root.optJSONObject(key) ?: continue
            if (key == "lo") continue
            if (dev.optBoolean("up", true) == false) continue
            names += dev.optString("name").ifBlank { key }
        }
        return names.sortedWith(compareBy({ it != "br-lan" }, { it }))
    }

    /** getRealtimeStats mode=load → 行列表（[ts, l1, l5, l15]，定点 ÷100；缺列跳过） */
    fun loadRows(result: JSONArray): List<LoadRow> {
        return (0 until result.length()).mapNotNull { i ->
            val row = result.optJSONArray(i) ?: return@mapNotNull null
            if (row.length() < 4) return@mapNotNull null
            LoadRow(
                ts = row.optLong(0),
                load1 = row.optDouble(1) / 100.0,
                load5 = row.optDouble(2) / 100.0,
                load15 = row.optDouble(3) / 100.0,
            )
        }
    }

    /** 窗口统计：当前=末值、平均、峰值；空列表返回 null（空态守卫） */
    fun windowStats(values: List<Double>): WindowStats? {
        if (values.isEmpty()) return null
        return WindowStats(
            current = values.last(),
            average = values.sum() / values.size,
            peak = values.max(),
        )
    }

    /** 本窗口传输（字节）= 速率曲线的梯形积分 Σ (rᵢ+rᵢ₊₁)/2·Δt，Δt≤0 跳过；
     *  值/时间戳长度不齐取短者（确定性计算，数据有源——速率采样即来源） */
    fun windowTransfer(values: List<Double>, timestamps: List<Long>): Long {
        val n = minOf(values.size, timestamps.size)
        if (n < 2) return 0L
        var bytes = 0.0
        for (i in 0 until n - 1) {
            val dt = timestamps[i + 1] - timestamps[i]
            if (dt <= 0) continue
            bytes += (values[i] + values[i + 1]) / 2.0 * dt
        }
        return bytes.roundToLong()
    }

    /** 负载值展示：%.2f（对齐旧 toFixed(2)，固定 Locale.US） */
    fun loadText(v: Double): String = String.format(Locale.US, "%.2f", v)
}
