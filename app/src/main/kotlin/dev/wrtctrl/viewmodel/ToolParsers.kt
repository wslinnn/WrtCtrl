package dev.wrtctrl.viewmodel

import androidx.annotation.StringRes
import dev.wrtctrl.R
import org.json.JSONArray
import org.json.JSONObject

// ── 数据模型 ──

/** 路由表行（/sbin/ip route show table all 输出的 token 解析） */
data class RouteRow(
    val family: String,
    val destination: String?,
    val gateway: String?,
    val device: String?,
    val src: String?,
    val scope: String?,
    val table: String?,
) {
    /** 类型徽章文案：default / 含掩码=network / 其余=host（local/broadcast 死分支不实现） */
    val typeRes: Int
        get() = when {
            destination == "default" -> R.string.route_type_default
            destination?.contains('/') == true -> R.string.route_type_network
            else -> R.string.route_type_host
        }
}

data class ProcessRow(
    val pid: String,
    val user: String,
    val ppid: String,
    val stat: String,
    /** %CPU 原文（含 %），cpuValue 用于排序 */
    val cpu: String,
    val memPercent: String,
    /** 内存展示「xx.xMB(p%)」 */
    val memoryText: String,
    /** 展示名：命令首 token 取 basename；内核线程 [xx] 原样 */
    val name: String,
    val command: String,
)

data class StartupRow(
    val name: String,
    /** 启动优先级（缺失=999，排序用） */
    val start: Int,
    val enabled: Boolean,
    val running: Boolean,
) {
    /** 徽章文案：运行中(正) / 已停止(enabled 未运行,警示) / 已禁用(中性) */
    val statusRes: Int
        get() = when {
            running -> R.string.startup_running
            enabled -> R.string.startup_stopped
            else -> R.string.startup_disabled
        }
}

data class ConnRow(
    val network: String,
    val protocol: String,
    val src: String,
    val sport: Int?,
    val dst: String,
    val dport: Int?,
    val bytes: Long,
    val packets: Long,
)

/** syslog/dmesg 行（core 已分类级别） */
data class LogLineUi(val text: String, val level: String)

/**
 * 工具页纯解析：JSON/文本进、结构化模型出，
 * JVM 单测覆盖；失败返回空/默认值。
 * local/broadcast 死分支与进程冗余分支不实现。
 */
internal object ToolParsers {

    // ── route ──

    /** 双族路由表：v4/v6 stdout 合并解析；family 标记进行（v6 失败传 null/空） */
    fun parseRoutes(stdout4: String?, stdout6: String?): List<RouteRow> =
        parseRouteFamily(stdout4, "ipv4") + parseRouteFamily(stdout6, "ipv6")

    private fun parseRouteFamily(stdout: String?, family: String): List<RouteRow> {
        if (stdout.isNullOrBlank()) return emptyList()
        return stdout.lines().mapNotNull { line ->
            parseRouteLine(line.trim(), family)
        }
    }

    /** token 行走解析：default/via/dev/src/scope/table + 目的地探测 */
    internal fun parseRouteLine(line: String, family: String): RouteRow? {
        if (line.isEmpty()) return null
        val parts = line.split(Regex("\\s+"))
        var destination: String? = null
        var gateway: String? = null
        var device: String? = null
        var src: String? = null
        var scope: String? = null
        var table: String? = null
        var i = 0
        while (i < parts.size) {
            when (val part = parts[i]) {
                "default" -> { destination = "default"; i++ }
                "via" -> { gateway = parts.getOrNull(++i); i++ }
                "dev" -> { device = parts.getOrNull(++i); i++ }
                "src" -> { src = parts.getOrNull(++i); i++ }
                "scope" -> { scope = parts.getOrNull(++i); i++ }
                "table" -> { table = parts.getOrNull(++i); i++ }
                else -> {
                    val looksLikeDestination = part.contains('/') ||
                        part.matches(IPV4_LITERAL) ||
                        part.contains(':')
                    if (destination == null && looksLikeDestination) {
                        destination = part
                    }
                    i++
                }
            }
        }
        if (destination == null && gateway == null && device == null) return null
        return RouteRow(family, destination, gateway, device, src, scope, table)
    }

    // ── process ──

    /** luci getProcessList → %CPU 降序；展开态由 ViewModel 按 PID 维护，此处不管 */
    fun parseProcesses(arr: JSONArray): List<ProcessRow> {
        val rows = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val cpuRaw = o.optString("%CPU")
            val vsz = o.optString("VSZ")
            val memPct = o.optString("%MEM")
            ProcessRow(
                pid = o.optString("PID"),
                user = o.optString("USER"),
                ppid = o.optString("PPID"),
                stat = o.optString("STAT"),
                cpu = cpuRaw,
                memPercent = memPct,
                memoryText = formatMemory(vsz, memPct),
                name = processName(o.optString("COMMAND")),
                command = o.optString("COMMAND"),
            )
        }
        return rows.sortedByDescending { it.cpu.removeSuffix("%").toDoubleOrNull() ?: 0.0 }
    }

    /** 命令展示名：内核线程 [xx] 原样；否则首 token 取 basename */
    internal fun processName(command: String): String {
        if (command.isBlank()) return "Unknown"
        if (command.startsWith("[") && command.endsWith("]")) return command
        val first = command.split(" ").first()
        return first.substringAfterLast('/')
    }

    /** 「12.5m」→ m 后缀乘 1024，纯数字按 KB；→ 一位小数 MB(p%） */
    internal fun formatMemory(vsz: String, memPercent: String): String {
        val number = Regex("(\\d+(?:\\.\\d+)?)").find(vsz)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        val kb = if (vsz.contains("m")) number * 1024 else number
        val mb = String.format(java.util.Locale.US, "%.1f", kb / 1024)
        return "${mb}MB($memPercent)"
    }

    // ── startup ──

    /** rc list（名称键控根对象）→ start 升序（缺 999），name 决次保证确定性（HashMap 键序） */
    fun parseStartup(data: JSONObject): List<StartupRow> {
        val rows = mutableListOf<StartupRow>()
        for (key in data.keys()) {
            val o = data.optJSONObject(key) ?: continue
            rows += StartupRow(
                name = key,
                start = o.optInt("start", 999),
                enabled = o.optBoolean("enabled", false),
                running = o.optBoolean("running", false),
            )
        }
        return rows.sortedWith(compareBy({ it.start }, { it.name }))
    }

    // ── conntrack ──

    /** getConntrackList → 回环过滤 + 字节数降序；(total, rows) */
    fun parseConntrack(arr: JSONArray): Pair<Int, List<ConnRow>> {
        val rows = (0 until arr.length()).mapNotNull { i ->
            val c = arr.optJSONObject(i) ?: return@mapNotNull null
            ConnRow(
                network = c.optString("layer3").uppercase(),
                protocol = c.optString("layer4").uppercase(),
                src = c.optString("src"),
                sport = c.optIntOrNull("sport"),
                dst = c.optString("dst"),
                dport = c.optIntOrNull("dport"),
                bytes = c.optLong("bytes", 0),
                packets = c.optLong("packets", 0),
            )
        }.filterNot { (it.src == "127.0.0.1" && it.dst == "127.0.0.1") || (it.src == "::1" && it.dst == "::1") }
            .sortedByDescending { it.bytes }
        return rows.size to rows
    }

    /** getRealtimeStats{mode:conntrack} 列 → 窗口统计（复用 StatisticsParsers，列 1=UDP 2=TCP 3=其它） */
    fun parseConntrackStats(points: JSONArray): Triple<WindowStats, WindowStats, WindowStats>? {
        if (points.length() == 0) return null
        fun col(idx: Int) = (0 until points.length()).mapNotNull { p ->
            points.optJSONArray(p)?.optDouble(idx)?.takeUnless { it.isNaN() } ?: 0.0
        }
        val udp = StatisticsParsers.windowStats(col(1)) ?: return null
        val tcp = StatisticsParsers.windowStats(col(2)) ?: return null
        val other = StatisticsParsers.windowStats(col(3)) ?: return null
        return Triple(udp, tcp, other)
    }

    /** network.rrdns lookup 响应（ip→hostname 名称键控） */
    fun parseRrdns(payload: JSONObject): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (ip in payload.keys()) {
            val name = payload.optString(ip)
            if (name.isNotBlank()) map[ip] = name
        }
        return map
    }

    // ── syslog ──

    /** core readSyslog/readDmesg → 行模型（级别串原样：err/warn/info） */
    fun parseLogLines(arr: JSONArray): List<LogLineUi> =
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            LogLineUi(text = o.optString("text"), level = o.optString("level", "info"))
        }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private val IPV4_LITERAL = Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$")
}
