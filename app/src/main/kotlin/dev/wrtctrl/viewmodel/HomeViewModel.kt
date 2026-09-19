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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.roundToLong

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
    // 网络信息卡
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
    /** 下拉刷新进行中 */
    val refreshing: Boolean = false,
)

/**
 * 首页仪表盘轮询：每 3s 一次并发 8 项（board / info / conntrack×2 / iface dump /
 * getTempInfo / getMountPoints / getCPUUsage）+ 带宽差分（getRealtimeStats）。
 * getCPUUsage 为 部分回退（CPU 环需求，见 ；轮询绑定本 ViewModel
 * 生命周期：离开主界面自动停止（行为偏差表 B1 修复的延伸）。
 * 带宽目标 = wan 优先（l3_device），无 wan 回落 br-lan（旧 getQuickBandwidthTarget）。
 * 解析逻辑全部在 HomeParsers（纯函数，JVM 单测覆盖）。
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

    /** 下拉刷新：立即补一轮拉取（与 3s 自动轮询并存；进行中重复触发忽略） */
    fun refresh() {
        if (_state.value.refreshing) return
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            pollOnce()
            _state.update { it.copy(refreshing = false) }
        }
    }

    private suspend fun pollOnce() {
        val poll = coroutineScope {
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
                model = poll.board?.let(HomeParsers::model) ?: state.model,
                hostname = poll.board?.optString("hostname", state.hostname) ?: state.hostname,
                version = poll.board?.let(HomeParsers::versionString) ?: state.version,
                architecture = poll.board?.optString("system", state.architecture) ?: state.architecture,
                target = poll.board?.optJSONObject("release")?.optString("target", state.target) ?: state.target,
                uptime = poll.info?.optLong("uptime")?.let(Format::duration) ?: state.uptime,
                load = poll.info?.let(HomeParsers::load) ?: state.load,
                cpuPercent = poll.cpu?.let(HomeParsers::cpuPercent) ?: state.cpuPercent,
                tempC = poll.temp?.let(HomeParsers::tempC) ?: state.tempC,
                memoryPercent = poll.info?.let(HomeParsers::memoryPercent) ?: state.memoryPercent,
                memoryDetail = poll.info?.let(HomeParsers::memoryDetail) ?: state.memoryDetail,
                connections = HomeParsers.connectionsText(poll.connCount, poll.connMax) ?: state.connections,
                wanIp = poll.ifaceDump?.let(HomeParsers::wanIp) ?: state.wanIp,
                lanIp = poll.ifaceDump?.let(HomeParsers::lanIp) ?: state.lanIp,
                gateway = poll.ifaceDump?.let(HomeParsers::gateway) ?: state.gateway,
                dns = poll.ifaceDump?.let(HomeParsers::dns) ?: state.dns,
                mounts = poll.mounts?.let(HomeParsers::mountList) ?: state.mounts,
            )
        }

        // 带宽依赖 iface dump 的目标设备
        poll.ifaceDump?.let { fetchBandwidth(it) }
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
        } catch (e: CancellationException) {
            throw e
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

    // 轮询容错吞掉业务异常，但 CancellationException 必须外抛（吞掉会破坏结构化取消）
    private suspend fun ubusSafe(objectName: String, method: String): JSONObject? = try {
        withContext(Dispatchers.IO) { WrtCore.callUbus(objectName, method) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun readSafe(path: String): String? = try {
        withContext(Dispatchers.IO) { WrtCore.readFile(path) }
    } catch (e: CancellationException) {
        throw e
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
}
