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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
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
    /** DataStore 持久化值已落地（编辑页以此为界同步本地初值，见 DashboardEditScreen） */
    val cardConfigLoaded: Boolean = false,
    /** 下拉刷新进行中 */
    val refreshing: Boolean = false,
)

/**
 * 首页仪表盘轮询：每 3s 一次并发 8 项（board / info / conntrack×2 / iface dump /
 * getTempInfo / getMountPoints / getCPUUsage）+ 带宽差分（getRealtimeStats）。
 * 
 * 轮询门控：pollingActive 默认关，由屏幕层 PollingGate 驱动（组合可见 × 前台）；
 * 循环先等一个周期再刷——进页/切设备的立即拉取（switchDevice）不与之重复。
 * 带宽目标 = wan 优先（l3_device），无 wan 回落 br-lan（旧 getQuickBandwidthTarget）。
 * 解析逻辑全部在 HomeParsers（纯函数，JVM 单测覆盖）。
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state

    private var bandwidthDevice: String? = null
    private val dashboardPrefs = DashboardPrefs(application)
    private var loadedDeviceId: String? = null

    /** 设备代次（reqSeq 守卫）：切设备 +1，在飞响应按代次丢弃，防旧设备数据覆盖新设备首拉 */
    private var generation = 0

    /** 轮询开关：页面可见才轮询（由 HomeScreen 的 PollingGate 驱动） */
    private val pollingActive = MutableStateFlow(false)

    fun setPollingActive(active: Boolean) {
        pollingActive.value = active
    }

    init {
        viewModelScope.launch {
            while (viewModelScope.isActive) {
                // 不可见时在此挂起（不占任何资源），回到可见等一个周期再刷；
                // delay 期间门控可能关闭：拉取前复查，避免切走后多发一轮
                pollingActive.first { it }
                delay(POLL_INTERVAL)
                if (pollingActive.value) pollOnce()
            }
        }
        viewModelScope.launch {
            dashboardPrefs.configFlow().collect { config ->
                _state.update {
                    it.copy(
                        cardOrder = config.order,
                        cardEnabled = config.enabled,
                        collapsed = config.collapsed,
                        cardConfigLoaded = true,
                    )
                }
            }
        }
    }

    /** 切换设备：重置回 loading 过渡态（与进 app 一致）并立即补一轮拉取——
     *  否则旧设备数据要挂到下个 3s 轮询节拍才被新数据覆盖，观感是"变化很慢"。
     *  卡片自定义三件套（顺序/显隐/折叠）跨设备保留 */
    fun switchDevice(deviceId: String?) {
        if (deviceId == loadedDeviceId) return
        loadedDeviceId = deviceId
        generation++
        bandwidthDevice = null
        _state.update {
            HomeUiState(
                cardOrder = it.cardOrder,
                cardEnabled = it.cardEnabled,
                collapsed = it.collapsed,
                cardConfigLoaded = it.cardConfigLoaded,
            )
        }
        viewModelScope.launch { pollOnce() }
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
        val gen = generation
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
        // 拉取期间设备已切换：整轮丢弃（失败项保留旧值语义不变）
        if (gen != generation) return
        _state.update { applyPoll(it, poll) }

        // 带宽依赖 iface dump 的目标设备
        poll.ifaceDump?.let { fetchBandwidth(it, gen) }
    }

    /** 一轮拉取结果 → 状态字段映射：各字段失败/缺失时保留旧值（静默保留） */
    private fun applyPoll(state: HomeUiState, poll: HomePoll): HomeUiState = state.copy(
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
        // 内容不变的列表保持同实例：引用稳定才能让未变化的卡在重组中被跳过
        mounts = poll.mounts?.let(HomeParsers::mountList)
            ?.takeIf { it != state.mounts } ?: state.mounts,
    )

    private suspend fun fetchBandwidth(ifaceDump: JSONObject, gen: Int) {
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
        // 时间戳锚定到设备墙钟：luci 实时统计的 ts 语义随固件而异（epoch / 路由 uptime），
        // 但都按真实秒推进——用「最后一次采样 ≈ 本次拉取时刻」线性平移，X 轴与查值标记
        // 的时间对一切语义都正确（若本就是 epoch，偏移仅为网络延迟，无影响）
        val wallNowSec = System.currentTimeMillis() / 1000
        val shiftSec = wallNowSec - ts.last()
        val wallTs = ts.map { it + shiftSec }
        if (gen != generation) return
        _state.update {
            it.copy(
                rxSeries = rx.toList(),
                txSeries = tx.toList(),
                timestamps = wallTs,
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

    private companion object {
        /** 轮询周期：与其他数据页一致（可见时静默刷新） */
        const val POLL_INTERVAL = 3000L
    }
}
