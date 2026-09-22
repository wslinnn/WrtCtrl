package dev.wrtctrl.viewmodel

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.wrtctrl.bridge.WrtCore
import dev.wrtctrl.util.Format
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

data class StatisticsUiState(
    /** 吞吐区（接口选择 + 速率窗口） */
    val interfaces: List<String> = emptyList(),
    val selectedDevice: String? = null,
    val rxSeries: List<Double> = emptyList(),
    val txSeries: List<Double> = emptyList(),
    val bwTimestamps: List<Long> = emptyList(),
    /** 负载区（1/5/15min 三线） */
    val loadRows: List<LoadRow> = emptyList(),
    /** 首拉进行中（各区块首份落定前显示加载态） */
    val bwLoading: Boolean = true,
    val loadLoading: Boolean = true,
    val refreshing: Boolean = false,
)

/**
 * 统计页状态（两节合一屏）：吞吐 + 负载同屏轮询，
 * 可见时 3s 静默刷新（模式同首页）；时间戳墙钟锚定同首页（getRealtimeStats ts 语义随固件而异）；
 * 切接口清曲线重拉；切设备整页失效。
 */
class StatisticsViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(StatisticsUiState())
    val state: StateFlow<StatisticsUiState> = _state

    private var loadedDeviceId: String? = null
    /** 设备/接口代次（reqSeq 守卫）：切设备或切接口 +1，在飞响应按代次丢弃 */
    private var generation = 0
    /** 时间锚点（秒）：带宽/负载的 ts 列语义随固件而异（epoch/uptime），平移到手机墙钟；
     *  一次定格复用——逐刷重锚会让整条序列时间戳随轮询漂移（轴标签/查值跳变）。切接口失效 */
    private var bwAnchorSec: Long? = null
    private var loadAnchorSec: Long? = null
    private val pollingActive = MutableStateFlow(false)

    fun setPollingActive(active: Boolean) {
        pollingActive.value = active
    }

    init {
        viewModelScope.launch {
            while (viewModelScope.isActive) {
                // delay 期间门控可能关闭：拉取前复查，避免切走后多发一轮
                pollingActive.first { it }
                delay(POLL_INTERVAL)
                if (!pollingActive.value) continue
                // 吞吐与负载互不依赖：并行收敛 tick 活跃窗口
                coroutineScope {
                    val bwJob = async { fetchBandwidth() }
                    val loadJob = async { fetchLoad() }
                    bwJob.await()
                    loadJob.await()
                }
            }
        }
    }

    fun ensureLoaded(deviceId: String?) {
        if (deviceId == loadedDeviceId) return
        loadedDeviceId = deviceId
        generation++
        _state.update { StatisticsUiState() }
        viewModelScope.launch {
            val gen = generation
            val devices = ubusSafe("luci-rpc", "getNetworkDevices") ?: JSONObject()
            val options = StatisticsParsers.interfaceOptions(devices)
            val initial = options.firstOrNull { it == "br-lan" } ?: options.firstOrNull()
            if (gen != generation) return@launch
            _state.update { it.copy(interfaces = options, selectedDevice = initial) }
            coroutineScope {
                val bwJob = async { fetchBandwidth(gen) }
                val loadJob = async { fetchLoad(gen) }
                bwJob.await()
                loadJob.await()
            }
        }
    }

    fun selectDevice(name: String) {
        if (_state.value.selectedDevice == name) return
        // 切接口不清曲线：旧接口曲线保留显示，新数据到达时单次原子替换。清空会让图表离开组合、modelProducer 销毁重建——切一次闪一次
        // 。代次失效防旧接口在飞响应回填新接口。
        generation++
        bwAnchorSec = null
        loadAnchorSec = null
        _state.update { it.copy(selectedDevice = name) }
        viewModelScope.launch { fetchBandwidth() }
    }

    fun refresh() {
        if (_state.value.refreshing) return
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            val startedAt = SystemClock.elapsedRealtime()
            coroutineScope {
                val bwJob = async { fetchBandwidth() }
                val loadJob = async { fetchLoad() }
                bwJob.await()
                loadJob.await()
            }
            holdRefreshSpin(startedAt)
            _state.update { it.copy(refreshing = false) }
        }
    }

    private suspend fun fetchBandwidth(gen: Int = generation) {
        val device = _state.value.selectedDevice ?: return
        try {
            val payload = ubusSafe("luci", "getRealtimeStats", JSONObject().put("mode", "interface").put("device", device))
                ?.optJSONArray("result")
            if (payload == null) {
                // 调用失败：静默保留现有曲线（静默保留），不清不闪
                return
            }
            val series = withContext(Dispatchers.IO) { Format.bandwidthRates(payload) }
            if (gen != generation) return
            if (series.rx.isEmpty()) {
                // 成功返回但无样本 = 新选接口确无数据：清空进等高占位（曲线归属必须正确）
                _state.update {
                    it.copy(
                        bwLoading = false,
                        rxSeries = emptyList(),
                        txSeries = emptyList(),
                        bwTimestamps = emptyList(),
                    )
                }
                return
            }
            val window = 60
            val rx = series.rx.takeLast(window)
            val tx = series.tx.takeLast(window)
            // 墙钟锚定（锚点一次定格，同 HomeViewModel.fetchBandwidth）：首刷算一次 shift 后复用，
            // 消除路由器缓冲 1s 粒度与轮询节奏不锁相导致的每刷 ±1s 时间漂移
            val anchor = bwAnchorSec
                ?: (System.currentTimeMillis() / 1000 - series.timestamps.last()).also { bwAnchorSec = it }
            val ts = series.timestamps.takeLast(window).map { it + anchor }
            // 断档截断（同首页）：停轮询期间设备缓冲冻结，恢复后新旧样本假连续，
            // 截掉断档前样本——峰值/本窗口积分只算连续窗口
            val (tailRx, tailTx, tailTs) = contiguousBandwidthTail(rx, tx, ts, POLL_INTERVAL / 1000 * 3 + 2)
            _state.update { it.copy(bwLoading = false, rxSeries = tailRx, txSeries = tailTx, bwTimestamps = tailTs) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("wrtctrl", "bandwidth stats failed: ${e.message}")
            if (gen == generation) _state.update { it.copy(bwLoading = false) }
        }
    }

    private suspend fun fetchLoad(gen: Int = generation) {
        try {
            // 调用失败静默保留旧曲线（与 fetchBandwidth 同语义：
            // 此前 `?: emptyList()` 会把瞬时失败当「成功但无样本」清空负载图）
            val payload = ubusSafe("luci", "getRealtimeStats", JSONObject().put("mode", "load"))
                ?.optJSONArray("result") ?: return
            // 解析+窗口收口整体放 IO；60 点窗口与断档截断对齐带宽口径——
            // 设备缓冲全量进来会让三线图画远多于带宽图的点，且停轮询期新旧样本假连续
            val anchored = withContext(Dispatchers.IO) {
                val rows = StatisticsParsers.loadRows(payload)
                val window = rows.takeLast(LOAD_WINDOW)
                // 墙钟锚定（锚点一次定格）同带宽：首刷算一次 shift 后复用，消除每刷时间漂移
                val anchor = loadAnchorSec ?: window.lastOrNull()?.let { last ->
                    (System.currentTimeMillis() / 1000 - last.ts).also { loadAnchorSec = it }
                } ?: 0L
                val ts = window.map { it.ts + anchor }
                val (series, tailTs) = contiguousLoadTail(
                    window.map { it.load1 },
                    window.map { it.load5 },
                    window.map { it.load15 },
                    ts,
                    POLL_INTERVAL / 1000 * 3 + 2,
                )
                tailTs.mapIndexed { i, t -> LoadRow(t, series[0][i], series[1][i], series[2][i]) }
            }
            if (gen != generation) return
            _state.update { it.copy(loadLoading = false, loadRows = anchored) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("wrtctrl", "load stats failed: ${e.message}")
            if (gen == generation) _state.update { it.copy(loadLoading = false) }
        }
    }

    private suspend fun ubusSafe(objectName: String, method: String, params: JSONObject = JSONObject()): JSONObject? =
        try {
            withContext(Dispatchers.IO) { WrtCore.callUbus(objectName, method, params) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("wrtctrl", "ubus $objectName.$method failed: ${e.message}")
            null
        }

    private companion object {
        const val POLL_INTERVAL = 3000L

        /** 负载曲线窗口：对齐带宽 60 点口径 */
        const val LOAD_WINDOW = 60
    }
}

/** 负载三线断档截断（与 HomeParsers.contiguousBandwidthTail 同算法——负载是三线，签名多一线） */
private fun contiguousLoadTail(
    load1: List<Double>,
    load5: List<Double>,
    load15: List<Double>,
    ts: List<Long>,
    gapLimitSec: Long,
): kotlin.Pair<List<List<Double>>, List<Long>> {
    var cut = 0
    for (i in 1 until ts.size) {
        if (ts[i] - ts[i - 1] > gapLimitSec) cut = i
    }
    if (cut == 0) return Pair(listOf(load1, load5, load15), ts)
    return Pair(
        listOf(load1.drop(cut), load5.drop(cut), load15.drop(cut)),
        ts.drop(cut),
    )
}
