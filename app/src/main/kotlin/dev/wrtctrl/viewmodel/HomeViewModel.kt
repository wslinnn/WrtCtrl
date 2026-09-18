package dev.wrtctrl.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.wrtctrl.bridge.WrtCore
import dev.wrtctrl.data.DashboardCardId
import dev.wrtctrl.data.DashboardConfig
import dev.wrtctrl.data.DashboardPrefs
import dev.wrtctrl.util.Format
import dev.wrtctrl.util.Format.bandwidthRates
import dev.wrtctrl.util.formatBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject

data class MountInfo(
    val device: String,
    val mount: String,
    val usagePercent: Int,
    val detail: String,
)

data class HomeUiState(
    val loading: Boolean = true,
    // 系统信息卡（CPU 负载与温度移入资源监控环，此处只留静态标识 + 运行时间）
    val model: String = "--",
    val hostname: String = "--",
    val version: String = "--",
    val architecture: String = "--",
    val target: String = "--",
    val uptime: String = "--",
    val load: String = "--",
    // 资源监控环：null = 数据不可得（无传感器/解析失败）→ 该环隐藏（空态守卫）
    val cpuPercent: Int? = null,
    val tempC: Int? = null,
    // 内存
    val memoryPercent: Int = 0,
    val memoryDetail: String = "--",
    // 网络状态卡
    val wanIp: String = "--",
    val lanIp: String = "--",
    val gateway: String = "--",
    val dns: String = "--",
    val connections: String = "--",
    // 存储
    val mounts: List<MountInfo> = emptyList(),
    // 实时带宽
    val rxRate: Long = 0,
    val txRate: Long = 0,
    val rxSeries: List<Double> = emptyList(),
    val txSeries: List<Double> = emptyList(),
    val timestamps: List<Long> = emptyList(),
    val bandwidthSource: String = "lan",
    // 卡片自定义
    val cardOrder: List<DashboardCardId> = DashboardPrefs.DEFAULT.order,
    val cardEnabled: Set<DashboardCardId> = DashboardPrefs.DEFAULT.enabled,
    val collapsed: Set<DashboardCardId> = DashboardPrefs.DEFAULT.collapsed,
    // 最近一次成功拉取时间（连续失败时停走 → 用户可感知数据冻结）
    val lastUpdated: Long? = null,
)

/**
 * 首页仪表盘：3s 轮询 8 项数据（对齐旧 home.vue 但剔除两个死轮询）。
 * 带宽目标 = wan 优先（l3_device），无 wan 回落 br-lan（旧 getQuickBandwidthTarget）。
 * 轮询绑定本 ViewModel 生命周期：离开主界面自动停止（行为偏差表 B1 修复的延伸）。
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state

    private var bandwidthDevice: String? = null
    private val dashboardPrefs = DashboardPrefs(application)

    init {
        viewModelScope.launch {
            pollOnce()
            while (viewModelScope.isActive) {
                delay(3000)
                pollOnce()
            }
        }
        viewModelScope.launch {
            dashboardPrefs.configFlow().collect { config ->
                _state.update {
                    it.copy(
                        cardOrder = config.order,
                        cardEnabled = config.enabled,
                        collapsed = config.collapsed,
                    )
                }
            }
        }
    }

    /** 编辑页即时保存（顺序 + 显隐；折叠态保持当前值不被动） */
    fun saveCardConfig(order: List<DashboardCardId>, enabled: Set<DashboardCardId>) {
        val collapsed = _state.value.collapsed
        viewModelScope.launch { dashboardPrefs.save(DashboardConfig(order, enabled, collapsed)) }
    }

    /** 卡头点击展开/收起：本地立即生效 + 异步持久化（跨重启记忆） */
    fun toggleCollapsed(cardId: DashboardCardId) {
        val newCollapsed = _state.value.collapsed
            .let { if (cardId in it) it - cardId else it + cardId }
        _state.update { it.copy(collapsed = newCollapsed) }
        val snapshot = _state.value
        viewModelScope.launch {
            dashboardPrefs.save(DashboardConfig(snapshot.cardOrder, snapshot.cardEnabled, newCollapsed))
        }
    }

    private suspend fun pollOnce() {
        val (board, info, connCount, connMax, ifaceDump, temp, mounts, cpu) = coroutineScope {
            val board = async { ubusSafe("system", "board") }
            val info = async { ubusSafe("system", "info") }
            val connCount = async { readSafe("/proc/sys/net/netfilter/nf_conntrack_count") }
            val connMax = async { readSafe("/proc/sys/net/netfilter/nf_conntrack_max") }
            val ifaceDump = async { ubusSafe("network.interface", "dump") }
            val temp = async { ubusSafe("luci", "getTempInfo") }
            val mounts = async { ubusSafe("luci", "getMountPoints") }
            val cpu = async { ubusSafe("luci", "getCPUUsage") }
            HomePoll(
                board.await(), info.await(), connCount.await(), connMax.await(),
                ifaceDump.await(), temp.await(), mounts.await(), cpu.await(),
            )
        }

        _state.update { state ->
            state.copy(
                loading = false,
                model = board?.model() ?: state.model,
                hostname = board?.optString("hostname", state.hostname) ?: state.hostname,
                version = board?.versionString() ?: state.version,
                architecture = board?.optString("system", state.architecture) ?: state.architecture,
                target = board?.optJSONObject("release")?.optString("target", state.target) ?: state.target,
                uptime = info?.optLong("uptime")?.let(Format::duration) ?: state.uptime,
                load = info?.loadString() ?: state.load,
                cpuPercent = cpu?.cpuPercent() ?: state.cpuPercent,
                tempC = temp?.tempC() ?: state.tempC,
                memoryPercent = info?.memoryPercent() ?: state.memoryPercent,
                memoryDetail = info?.memoryDetail() ?: state.memoryDetail,
                connections = connectionsText(connCount, connMax) ?: state.connections,
                wanIp = ifaceDump?.wanIp() ?: state.wanIp,
                lanIp = ifaceDump?.lanIp() ?: state.lanIp,
                gateway = ifaceDump?.gateway() ?: state.gateway,
                dns = ifaceDump?.dns() ?: state.dns,
                mounts = mounts?.mountList() ?: state.mounts,
                // 任一主数据源成功才推进时间戳：全失败（断线）时旧时间停走，用户可感知数据冻结
                lastUpdated = if (board != null || info != null || ifaceDump != null) {
                    System.currentTimeMillis()
                } else {
                    state.lastUpdated
                },
            )
        }

        // 带宽依赖 iface dump 的目标设备
        ifaceDump?.let { fetchBandwidth(it) }
    }

    private suspend fun fetchBandwidth(ifaceDump: JSONObject) {
        val interfaces = ifaceDump.optJSONArray("interface") ?: return
        var wanDevice: String? = null
        for (i in 0 until interfaces.length()) {
            val entry = interfaces.optJSONObject(i) ?: continue
            if (entry.optString("interface") == "wan") {
                wanDevice = entry.optString("l3_device", entry.optString("device", "wan"))
                break
            }
        }
        val target = wanDevice ?: "br-lan"
        val source = if (wanDevice != null) "wan" else "lan"
        if (target != bandwidthDevice) {
            // 目标切换（wan↔br-lan）时清空序列，避免混入旧设备的差分
            bandwidthDevice = target
            _state.update {
                it.copy(
                    rxSeries = emptyList(),
                    txSeries = emptyList(),
                    timestamps = emptyList(),
                    rxRate = 0,
                    txRate = 0,
                    bandwidthSource = source,
                )
            }
        }
        val payload = try {
            withContext(Dispatchers.IO) {
                WrtCore.callUbus("luci", "getRealtimeStats", JSONObject().put("mode", "interface").put("device", target), 3000)
            }
        } catch (e: Exception) {
            return
        }
        val samples = payload.optJSONArray("result") ?: return
        val series = bandwidthRates(samples)
        if (series.timestamps.isEmpty()) return
        var rx = series.rx; var tx = series.tx; var ts = series.timestamps
        if (rx.size > 60) {
            rx = rx.takeLast(60).toMutableList()
            tx = tx.takeLast(60).toMutableList()
            ts = ts.takeLast(60).toMutableList()
        }
        _state.update {
            it.copy(
                rxSeries = rx.toList(),
                txSeries = tx.toList(),
                timestamps = ts.toList(),
                rxRate = rx.last().roundToLong(),
                txRate = tx.last().roundToLong(),
                bandwidthSource = source,
            )
        }
    }

    private suspend fun ubusSafe(objectName: String, method: String): JSONObject? = try {
        withContext(Dispatchers.IO) { WrtCore.callUbus(objectName, method) }
    } catch (e: Exception) {
        null
    }

    private suspend fun readSafe(path: String): String? = try {
        withContext(Dispatchers.IO) { WrtCore.readFile(path) }
    } catch (e: Exception) {
        null
    }

    private data class HomePoll(
        val board: JSONObject?,
        val info: JSONObject?,
        val connCount: String?,
        val connMax: String?,
        val ifaceDump: JSONObject?,
        val temp: JSONObject?,
        val mounts: JSONObject?,
        val cpu: JSONObject?,
    )

    private fun JSONObject.model(): String {
        val distribution = optJSONObject("release")?.optString("distribution", "OpenWrt")?.ifBlank { "OpenWrt" } ?: "OpenWrt"
        val version = optJSONObject("release")?.optString("version") ?: ""
        val kernel = optString("kernel")
        return optString("model").ifBlank { distribution }
    }

    private fun JSONObject.versionString(): String {
        val release = optJSONObject("release")
        val distribution = release?.optString("distribution", "OpenWrt")?.ifBlank { "OpenWrt" } ?: "OpenWrt"
        val version = release?.optString("version") ?: ""
        val kernel = optString("kernel")
        val base = if (version.isNotBlank()) "$distribution $version($kernel)" else "$distribution ($kernel)"
        return base.ifBlank { "--" }
    }

    private fun JSONObject.loadString(): String? {
        val arr = optJSONArray("load") ?: return null
        if (arr.length() < 3) return null
        return (0 until 3).joinToString(" ") { String.format("%.2f", arr.optDouble(it) / 65536.0) }
    }

    /** CPU 使用率（%）。数据源 = luci getCPUUsage 的 cpuusage 字段——部分回退：
     *  原字段因无人展示按死代码砍除，现 CPU 环有真实需求复活为活调用；
     *  多分支格式兼容不保留，只走单条解析路径，失败返回 null 隐藏环。 */
    private fun JSONObject.cpuPercent(): Int? {
        val raw = opt("cpuusage")?.toString() ?: return null
        val m = Regex("([0-9]+(?:\\.[0-9]+)?)").find(raw) ?: return null
        var v = m.groupValues[1].toDoubleOrNull() ?: return null
        if (v <= 1.0) v *= 100.0
        return v.coerceIn(0.0, 100.0).roundToInt()
    }

    /** 温度（℃）。取 tempinfo 首个数字；无传感器/为 0 → null，调用方隐藏温度环 */
    private fun JSONObject.tempC(): Int? {
        val raw = opt("tempinfo")?.toString() ?: return null
        val m = Regex("([0-9]+(?:\\.[0-9]+)?)").find(raw) ?: return null
        val v = m.groupValues[1].toDoubleOrNull() ?: return null
        if (v <= 0.0) return null
        return v.roundToInt()
    }

    private fun JSONObject.memoryPercent(): Int {
        val memory = optJSONObject("memory") ?: return 0
        val total = memory.optLong("total")
        if (total <= 0) return 0
        val used = total - memory.optLong("available")
        return (used.toDouble() / total * 100).roundToLong().toInt()
    }

    private fun JSONObject.memoryDetail(): String {
        val memory = optJSONObject("memory") ?: return "--"
        val total = memory.optLong("total")
        val used = total - memory.optLong("available")
        return "${formatBytes(used)} / ${formatBytes(total)}"
    }

    private fun connectionsText(count: String?, max: String?): String? {
        val current = count?.trim()?.toIntOrNull() ?: return null
        val maxV = max?.trim()?.toIntOrNull() ?: 0
        return "$current / $maxV"
    }

    private fun JSONObject.wanIp(): String? = ipOf("wan")
    private fun JSONObject.lanIp(): String? = ipOf("lan")

    private fun JSONObject.ipOf(name: String): String? {
        val interfaces = optJSONArray("interface") ?: return null
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

    private fun JSONObject.gateway(): String? {
        val interfaces = optJSONArray("interface") ?: return null
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

    private fun JSONObject.dns(): String? {
        val interfaces = optJSONArray("interface") ?: return null
        for (i in 0 until interfaces.length()) {
            val entry = interfaces.optJSONObject(i) ?: continue
            if (entry.optString("interface") != "wan") continue
            val servers = entry.optJSONArray("dns-server") ?: continue
            val parts = (0 until minOf(servers.length(), 2)).mapNotNull { servers.optString(it).takeIf(String::isNotBlank) }
            if (parts.isNotEmpty()) return parts.joinToString(", ")
        }
        return null
    }

    private fun JSONObject.mountList(): List<MountInfo> {
        val result = optJSONArray("result") ?: return emptyList()
        val list = (0 until result.length()).mapNotNull { m ->
            val entry = result.optJSONObject(m) ?: return@mapNotNull null
            val total = entry.optLong("size")
            val free = entry.optLong("free")
            val used = total - free
            val percent = if (total > 0) (used.toDouble() / total * 100).roundToLong().toInt() else 0
            MountInfo(
                device = entry.optString("device", "--").ifBlank { "--" },
                mount = entry.optString("mount", "--").ifBlank { "--" },
                usagePercent = percent,
                detail = "${formatBytes(used)} / ${formatBytes(total)}",
            )
        }
        return list
            .filter { it.mount != "/" && it.mount != "/dev" }
            .sortedBy { when (it.mount) { "/overlay" -> 0; "/tmp" -> 1; else -> 2 } }
    }
}
