package dev.wrtctrl.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.wrtctrl.bridge.WrtCore
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
    // 系统状态卡
    val model: String = "--",
    val hostname: String = "--",
    val version: String = "--",
    val architecture: String = "--",
    val target: String = "--",
    val uptime: String = "--",
    val load: String = "--",
    val temperature: String = "--",
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

    init {
        viewModelScope.launch {
            pollOnce()
            while (viewModelScope.isActive) {
                delay(3000)
                pollOnce()
            }
        }
    }

    private suspend fun pollOnce() {
        val (board, info, connCount, connMax, ifaceDump, temp, mounts) = coroutineScope {
            val board = async { ubusSafe("system", "board") }
            val info = async { ubusSafe("system", "info") }
            val connCount = async { readSafe("/proc/sys/net/netfilter/nf_conntrack_count") }
            val connMax = async { readSafe("/proc/sys/net/netfilter/nf_conntrack_max") }
            val ifaceDump = async { ubusSafe("network.interface", "dump") }
            val temp = async { ubusSafe("luci", "getTempInfo") }
            val mounts = async { ubusSafe("luci", "getMountPoints") }
            HomePoll(
                board.await(), info.await(), connCount.await(), connMax.await(),
                ifaceDump.await(), temp.await(), mounts.await(),
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
                memoryPercent = info?.memoryPercent() ?: state.memoryPercent,
                memoryDetail = info?.memoryDetail() ?: state.memoryDetail,
                temperature = temp?.optString("tempinfo", state.temperature) ?: state.temperature,
                connections = connectionsText(connCount, connMax) ?: state.connections,
                wanIp = ifaceDump?.wanIp() ?: state.wanIp,
                lanIp = ifaceDump?.lanIp() ?: state.lanIp,
                gateway = ifaceDump?.gateway() ?: state.gateway,
                dns = ifaceDump?.dns() ?: state.dns,
                mounts = mounts?.mountList() ?: state.mounts,
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
        if (target != bandwidthDevice) {
            // 目标切换（wan↔br-lan）时清空序列，避免混入旧设备的差分
            bandwidthDevice = target
            _state.update { it.copy(rxSeries = emptyList(), txSeries = emptyList(), rxRate = 0, txRate = 0) }
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
        var rx = series.rx; var tx = series.tx
        if (rx.size > 60) {
            rx = rx.takeLast(60).toMutableList()
            tx = tx.takeLast(60).toMutableList()
        }
        _state.update {
            it.copy(
                rxSeries = rx.toList(),
                txSeries = tx.toList(),
                rxRate = rx.last().roundToLong(),
                txRate = tx.last().roundToLong(),
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
