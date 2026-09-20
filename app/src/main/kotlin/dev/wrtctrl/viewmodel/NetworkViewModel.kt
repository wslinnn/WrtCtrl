package dev.wrtctrl.viewmodel

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.wrtctrl.bridge.WrtCore
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
import org.json.JSONObject

data class NetworkUiState(
    /** 首次拉取（进页到首批数据/失败落定前居中转圈） */
    val loading: Boolean = true,
    /** 下拉刷新指示（总时长 = max(拉取, 400ms)，见 holdRefreshSpin） */
    val refreshing: Boolean = false,
    val ifaces: List<IfaceInfo> = emptyList(),
    val deviceGroups: List<DeviceGroup> = emptyList(),
    val radios: List<RadioInfo> = emptyList(),
    /** 无线已成功拉取过（首进无线 Tab 或下拉刷新才再拉） */
    val wirelessLoaded: Boolean = false,
    /** 接口/设备最近一次拉取失败（列表为空时以失败文案区分「暂无数据」） */
    val loadFailed: Boolean = false,
    /** 无线最近一次拉取失败（首拉失败以失败文案区分「没有无线设备」） */
    val wirelessFailed: Boolean = false,
)

/**
 * 网络页状态：三个 ubus 调用。
 * 轮询：页面可见期间每 3s 静默刷新
 * 当前 Tab 对应数据（接口/设备 Tab→dump+devices，无线 Tab→wireless），
 * 离开页面/后台暂停（屏幕层 PollingGate 双门控）。
 * 拉取失败静默保留旧值，仅置 failed 标志供空列表时显示失败文案；
 * 切设备整页失效重拉+ generation 守卫丢弃在飞的旧设备响应。
 */
class NetworkViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(NetworkUiState())
    val state: StateFlow<NetworkUiState> = _state

    private var loadedDeviceId: String? = null

    /** 设备代次（reqSeq 守卫）：切设备 +1，在飞响应按代次丢弃 */
    private var generation = 0

    /** 轮询开关与当前 Tab（由屏幕层 PollingGate 驱动） */
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
                // 挂起直至页面可见；先等一个周期再刷——进页的手动拉取不重复
                pollingActive.first { it }
                delay(POLL_INTERVAL)
                if (polledTab == 2) loadWirelessNow() else loadNow()
            }
        }
    }

    /** 设备切换失效重拉：无线数据一并失效，回到无线 Tab 会重新拉取；同设备重复进入不重拉 */
    fun ensureLoaded(deviceId: String?) {
        if (deviceId != loadedDeviceId) {
            loadedDeviceId = deviceId
            generation++
            _state.update { it.copy(radios = emptyList(), wirelessLoaded = false, wirelessFailed = false, loadFailed = false) }
            load()
        }
    }

    /** 接口 + 设备两 Tab 共用一份拉取（调用 1+2） */
    fun load() {
        viewModelScope.launch { loadNow() }
    }

    /** 无线（调用 3）：首次进入无线 Tab 或下拉刷新才再拉 */
    fun loadWireless() {
        if (_state.value.wirelessLoaded) return
        viewModelScope.launch { loadWirelessNow() }
    }

    /** 下拉刷新：全量三调用，指示器总时长 = max(数据落地, 400ms) */
    fun refresh() {
        if (_state.value.refreshing) return
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true, wirelessLoaded = false) }
            val startedAt = SystemClock.elapsedRealtime()
            loadNow()
            loadWirelessNow()
            holdRefreshSpin(startedAt)
            _state.update { it.copy(refreshing = false) }
        }
    }

    /** 接口 + 设备：双调用一成败败（部分失败按整轮失败保留旧值，不出现半新半旧） */
    private suspend fun loadNow() {
        val gen = generation
        val dump = ubusSafe("network.interface", "dump")
        val devices = ubusSafe("luci-rpc", "getNetworkDevices")
        if (dump == null || devices == null) {
            if (gen == generation) _state.update { it.copy(loading = false, loadFailed = true) }
            return
        }
        val ifaces: List<IfaceInfo>
        val groups: List<DeviceGroup>
        try {
            ifaces = NetworkParsers.ifaceList(dump, devices)
            groups = NetworkParsers.deviceGroups(devices)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("wrtctrl", "network page load failed: ${e.message}")
            if (gen == generation) _state.update { it.copy(loading = false, loadFailed = true) }
            return
        }
        if (gen != generation) return
        _state.update {
            it.copy(loading = false, ifaces = ifaces, deviceGroups = groups, loadFailed = false)
        }
    }

    /** 无线：失败保留旧列表，仅置 wirelessFailed 供空态分支 */
    private suspend fun loadWirelessNow() {
        val gen = generation
        val payload = ubusSafe("luci-rpc", "getWirelessDevices")
        val radios = payload?.let {
            try {
                NetworkParsers.radios(it)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("wrtctrl", "wireless load failed: ${e.message}")
                null
            }
        }
        if (radios == null) {
            if (gen == generation) _state.update { it.copy(wirelessFailed = true) }
            return
        }
        if (gen != generation) return
        _state.update { it.copy(radios = radios, wirelessLoaded = true, wirelessFailed = false) }
    }

    private suspend fun ubusSafe(objectName: String, method: String): JSONObject? = try {
        withContext(Dispatchers.IO) { WrtCore.callUbus(objectName, method) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.w("wrtctrl", "ubus $objectName.$method failed: ${e.message}")
        null
    }

    private companion object {
        /** 轮询周期：与首页一致（可见时静默刷新） */
        const val POLL_INTERVAL = 3000L
    }
}
