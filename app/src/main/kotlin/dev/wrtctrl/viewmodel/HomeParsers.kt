package dev.wrtctrl.viewmodel

import dev.wrtctrl.util.Format
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import org.json.JSONObject

data class MountInfo(
    val device: String,
    val mount: String,
    val usagePercent: Int,
    val detail: String,
)

/** 首页网络卡的接口状态 chip：name 接口名，up UP/DOWN */
data class IfaceChip(val name: String, val up: Boolean)

/**
 * 首页轮询响应的纯解析函数：JSONObject 进、标量/列表出，无 Android 依赖——
 * 独立成单元以便 JVM 单测覆盖（本地单测经 testImplementation 的真 org.json 运行，
 * 见 app/build.gradle.kts）。解析失败一律返回 null/默认值而不是抛异常：
 * 轮询的容错策略是「保留旧值」。
 */
internal object HomeParsers {

    /** 轮询周期调用的高频解析，正则常量化避免每 3s 重复编译 */
    private val NUMERIC = Regex("([0-9]+(?:\\.[0-9]+)?)")

    /** 设备型号；缺失回落发行版名（板卡厂常不写 model） */
    fun model(board: JSONObject): String {
        val distribution = board.optJSONObject("release")
            ?.optString("distribution", "OpenWrt")?.ifBlank { "OpenWrt" } ?: "OpenWrt"
        return board.optString("model").ifBlank { distribution }
    }

    /** 固件短版本号（release.version，如 23.05.5）；缺失 null 不显 chip */
    fun releaseVersion(board: JSONObject): String? {
        val v = board.optJSONObject("release")?.optString("version")?.trim()
        return v?.takeIf { it.isNotEmpty() }
    }

    fun memoryPercent(info: JSONObject): Int {
        val memory = info.optJSONObject("memory") ?: return 0
        val total = memory.optLong("total")
        if (total <= 0) return 0
        val used = total - memory.optLong("available")
        return (used.toDouble() / total * 100).roundToLong().toInt().coerceIn(0, 100)
    }

    fun memoryDetail(info: JSONObject): String {
        val memory = info.optJSONObject("memory") ?: return "--"
        val total = memory.optLong("total")
        val used = total - memory.optLong("available")
        return "${Format.bytesCompact(used)} / ${Format.bytesCompact(total)}"
    }

    /** CPU 使用率（%）。数据源 = luci getCPUUsage 的 cpuusage 字段——部分回退：
     *  原字段因无人展示按死代码砍除，现 CPU 环有真实需求复活为活调用；
     *  多分支格式兼容不保留，只走单条解析路径，失败返回 null 隐藏环。 */
    fun cpuPercent(cpu: JSONObject): Int? {
        val raw = cpu.opt("cpuusage")?.toString() ?: return null
        val m = NUMERIC.find(raw) ?: return null
        var v = m.groupValues[1].toDoubleOrNull() ?: return null
        if (v <= 1.0) v *= 100.0
        return v.coerceIn(0.0, 100.0).roundToInt()
    }

    /** 温度（℃）。取 tempinfo 首个数字；无传感器/为 0 → null，调用方隐藏温度环 */
    fun tempC(temp: JSONObject): Int? {
        val raw = temp.opt("tempinfo")?.toString() ?: return null
        val m = NUMERIC.find(raw) ?: return null
        val v = m.groupValues[1].toDoubleOrNull() ?: return null
        if (v <= 0.0) return null
        return v.roundToInt()
    }

    /** NAT 会话计数数值对（count/max，进度条分母）；任一文件缺失/非数 → null */
    fun connections(count: String?, max: String?): Pair<Int, Int>? {
        val current = count?.trim()?.toIntOrNull() ?: return null
        val maxV = max?.trim()?.toIntOrNull() ?: return null
        return current to maxV
    }

    /** 接口状态 chip（UP/DOWN）：interface 是数组（顺序稳定，不涉 HashMap 键序），剔除 loopback */
    fun ifaceChips(dump: JSONObject): List<IfaceChip> {
        val interfaces = dump.optJSONArray("interface") ?: return emptyList()
        return (0 until interfaces.length()).mapNotNull { i ->
            val entry = interfaces.optJSONObject(i) ?: return@mapNotNull null
            val name = entry.optString("interface")
            if (name.isBlank() || name == "loopback") null else IfaceChip(name, entry.optBoolean("up", false))
        }
    }

    fun wanIp(dump: JSONObject): String? = ipOf(dump, "wan")

    /** 默认路由（target=0.0.0.0/0）的 nexthop */
    fun gateway(dump: JSONObject): String? {
        val interfaces = dump.optJSONArray("interface") ?: return null
        for (i in 0 until interfaces.length()) {
            val entry = interfaces.optJSONObject(i) ?: continue
            if (entry.optString("interface") != "wan") continue
            val routes = entry.optJSONArray("route") ?: continue
            for (r in 0 until routes.length()) {
                val route = routes.optJSONObject(r) ?: continue
                if (route.optString("target") == "0.0.0.0" && route.optInt("mask") == 0) {
                    return route.optString("nexthop")
                }
            }
        }
        return null
    }

    /** DNS 服务器（最多展示两条，逗号连接） */
    fun dns(dump: JSONObject): String? {
        val interfaces = dump.optJSONArray("interface") ?: return null
        for (i in 0 until interfaces.length()) {
            val entry = interfaces.optJSONObject(i) ?: continue
            if (entry.optString("interface") != "wan") continue
            val servers = entry.optJSONArray("dns-server") ?: continue
            val parts = (0 until minOf(servers.length(), 2))
                .mapNotNull { servers.optString(it).takeIf(String::isNotBlank) }
            if (parts.isNotEmpty()) return parts.joinToString(", ")
        }
        return null
    }

    /** 挂载点列表：剔除 / 与 /dev，overlay/tmp 优先；用量钳制 0–100 */
    fun mountList(mounts: JSONObject): List<MountInfo> {
        val result = mounts.optJSONArray("result") ?: return emptyList()
        val list = (0 until result.length()).mapNotNull { m ->
            val entry = result.optJSONObject(m) ?: return@mapNotNull null
            val total = entry.optLong("size")
            val free = entry.optLong("free")
            val used = total - free
            val percent = if (total > 0) (used.toDouble() / total * 100).roundToLong().toInt().coerceIn(0, 100) else 0
            MountInfo(
                device = entry.optString("device", "--").ifBlank { "--" },
                mount = entry.optString("mount", "--").ifBlank { "--" },
                usagePercent = percent,
                detail = "${Format.bytesCompact(used)} / ${Format.bytesCompact(total)}",
            )
        }
        return list
            .filter { it.mount != "/" && it.mount != "/dev" }
            .sortedBy { when (it.mount) { "/overlay" -> 0; "/tmp" -> 1; else -> 2 } }
    }

    private fun ipOf(dump: JSONObject, name: String): String? {
        val interfaces = dump.optJSONArray("interface") ?: return null
        for (i in 0 until interfaces.length()) {
            val entry = interfaces.optJSONObject(i) ?: continue
            if (entry.optString("interface") == name) {
                val v4 = entry.optJSONArray("ipv4-address")?.optJSONObject(0) ?: return null
                val address = v4.optString("address")
                if (address.isBlank()) return null
                return "$address/${v4.optInt("mask")}"
            }
        }
        return null
    }
}

/** 带宽序列断档截断（文件级纯函数，不占用 HomeParsers 的函数数）：设备端实时统计是
 *  「调用时采样」——app 停轮询期间设备缓冲冻结在旧时刻，恢复轮询后新旧样本直接拼接出
 *  假连续（X 轴 20:18:13 直跳 20:22:34 的根因）。相邻采样间隔超过 gapLimitSec 即视为
 *  断档，截掉最后一次断档之前的全部样本：丢弃的是缺测区间而非近似，峰值随之只统计
 *  当前连续窗口。 */
internal fun contiguousBandwidthTail(
    rx: List<Double>,
    tx: List<Double>,
    ts: List<Long>,
    gapLimitSec: Long,
): Triple<List<Double>, List<Double>, List<Long>> {
    var cut = 0
    for (i in 1 until ts.size) {
        if (ts[i] - ts[i - 1] > gapLimitSec) cut = i
    }
    if (cut == 0) return Triple(rx, tx, ts)
    return Triple(rx.drop(cut), tx.drop(cut), ts.drop(cut))
}
