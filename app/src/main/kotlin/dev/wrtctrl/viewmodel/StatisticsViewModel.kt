package dev.wrtctrl.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.wrtctrl.bridge.WrtCore
import dev.wrtctrl.util.Format
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class StatisticsUiState(
    /** 带宽 Tab */
    val interfaces: List<String> = emptyList(),
    val selectedDevice: String? = null,
    val rxSeries: List<Double> = emptyList(),
    val txSeries: List<Double> = emptyList(),
    val bwTimestamps: List<Long> = emptyList(),
    /** 负载 Tab */
    val loadRows: List<LoadRow> = emptyList(),
    /** 首拉进行中（各 Tab 首份落定前显示加载态） */
    val bwLoading: Boolean = true,
    val loadLoading: Boolean = true,
    val refreshing: Boolean = false,
)

/**
 * 统计页状态：带宽/负载双 Tab，可见时 3s 静默轮询
 * （模式同首页）；时间戳墙钟锚定同首页（getRealtimeStats ts 语义随固件而异）；
 * 切接口清曲线重拉；切设备整页失效。
 */
class StatisticsViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(StatisticsUiState())
    val state: StateFlow<StatisticsUiState> = _state

    private var loadedDeviceId: String? = null
    private val pollingActive = MutableStateFlow(false)
    private var polledTab = 0

    fun setPollingActive(active: Boolean) {
        pollingActive.value = active
    }

    fun onTab(tab: Int) {
        polledTab = tab
    }

    init {
        viewModelScope.launch {
            while (viewModelScope.isActive) {
                pollingActive.first { it }
                delay(POLL_INTERVAL)
                if (polledTab == 0) fetchBandwidth() else fetchLoad()
            }
        }
    }

    fun ensureLoaded(deviceId: String?) {
        if (deviceId == loadedDeviceId) return
        loadedDeviceId = deviceId
        _state.update { StatisticsUiState() }
        viewModelScope.launch {
            val devices = ubusSafe("luci-rpc", "getNetworkDevices") ?: JSONObject()
            val options = StatisticsParsers.interfaceOptions(devices)
            val initial = options.firstOrNull { it == "br-lan" } ?: options.firstOrNull()
            _state.update { it.copy(interfaces = options, selectedDevice = initial) }
            initial?.let { fetchBandwidth() }
            fetchLoad()
        }
    }

    fun selectDevice(name: String) {
        if (_state.value.selectedDevice == name) return
        _state.update { it.copy(selectedDevice = name, rxSeries = emptyList(), txSeries = emptyList(), bwTimestamps = emptyList()) }
        viewModelScope.launch { fetchBandwidth() }
    }

    fun refresh(tab: Int) {
        if (_state.value.refreshing) return
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            if (tab == 0) fetchBandwidth() else fetchLoad()
            delay(400)
            _state.update { it.copy(refreshing = false) }
        }
    }

    private suspend fun fetchBandwidth() {
        val device = _state.value.selectedDevice ?: return
        try {
            val payload = ubusSafe("luci", "getRealtimeStats", JSONObject().put("mode", "interface").put("device", device))
                ?.optJSONArray("result") ?: JSONArray()
            val series = Format.bandwidthRates(payload)
            if (series.rx.isEmpty()) {
                _state.update { it.copy(bwLoading = false) }
                return
            }
            val window = 60
            val rx = series.rx.takeLast(window)
            val tx = series.tx.takeLast(window)
            // 墙钟锚定：最后采样 ≈ 本次拉取时刻（同 HomeViewModel.fetchBandwidth）
            val shift = System.currentTimeMillis() / 1000 - series.timestamps.last()
            val ts = series.timestamps.takeLast(window).map { it + shift }
            _state.update { it.copy(bwLoading = false, rxSeries = rx, txSeries = tx, bwTimestamps = ts) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("wrtctrl", "bandwidth stats failed: ${e.message}")
            _state.update { it.copy(bwLoading = false) }
        }
    }

    private suspend fun fetchLoad() {
        try {
            val payload = ubusSafe("luci", "getRealtimeStats", JSONObject().put("mode", "load"))
                ?.optJSONArray("result")
            val rows = payload?.let(StatisticsParsers::loadRows) ?: emptyList()
            // 墙钟锚定同带宽：最后采样 ≈ 本次拉取时刻（ts 语义随固件而异）
            val shift = System.currentTimeMillis() / 1000 - (rows.lastOrNull()?.ts ?: 0L)
            val anchored = rows.map { it.copy(ts = it.ts + shift) }
            _state.update { it.copy(loadLoading = false, loadRows = anchored) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("wrtctrl", "load stats failed: ${e.message}")
            _state.update { it.copy(loadLoading = false) }
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
    }
}
