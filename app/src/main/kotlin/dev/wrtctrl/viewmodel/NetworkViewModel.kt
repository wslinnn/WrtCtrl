package dev.wrtctrl.viewmodel

import android.app.Application
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
    /** 下拉刷新指示（最短展示 400ms，见门控页同款实测教训） */
    val refreshing: Boolean = false,
    val ifaces: List<IfaceInfo> = emptyList(),
    val deviceGroups: List<DeviceGroup> = emptyList(),
    val radios: List<RadioInfo> = emptyList(),
    /** 无线已拉取过（首次进无线 Tab 或下拉刷新才再拉，K8 无轮询纪律） */
    val wirelessLoaded: Boolean = false,
)

/**
 * 网络页状态（客户端页同款轮询纪律）：三个 ubus 调用。
 * 轮询：页面可见期间每 3s 静默刷新
 * 当前 Tab 对应数据（接口/设备 Tab→dump+devices，无线 Tab→wireless），
 * 离开页面/后台暂停（Bottom Tab 组合级可见性 + 生命周期门控）。
 * 拉取失败静默保留旧值（ 错误链只进 logcat）；切设备整页失效重拉。
 */
class NetworkViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(NetworkUiState())
    val state: StateFlow<NetworkUiState> = _state

    private var loadedDeviceId: String? = null

    /** 轮询开关与当前 Tab（页面可见性/生命周期由屏幕层驱动） */
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
            _state.update { it.copy(radios = emptyList(), wirelessLoaded = false) }
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

    /** 下拉刷新：全量三调用，指示器等数据落地后再走最短 400ms 展示 */
    fun refresh() {
        if (_state.value.refreshing) return
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true, wirelessLoaded = false) }
            loadNow()
            loadWirelessNow()
            delay(400)
            _state.update { it.copy(refreshing = false) }
        }
    }

    private suspend fun loadNow() {
        var ifaces: List<IfaceInfo> = emptyList()
        var groups: List<DeviceGroup> = emptyList()
        try {
            val dump = ubusSafe("network.interface", "dump") ?: JSONObject()
            val devices = ubusSafe("luci-rpc", "getNetworkDevices") ?: JSONObject()
            ifaces = NetworkParsers.ifaceList(dump, devices)
            groups = NetworkParsers.deviceGroups(devices)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("wrtctrl", "network page load failed: ${e.message}")
        }
        _state.update {
            it.copy(loading = false, ifaces = ifaces, deviceGroups = groups)
        }
    }

    private suspend fun loadWirelessNow() {
        val radios = try {
            val payload = ubusSafe("luci-rpc", "getWirelessDevices") ?: JSONObject()
            NetworkParsers.radios(payload)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("wrtctrl", "wireless load failed: ${e.message}")
            emptyList()
        }
        _state.update { it.copy(radios = radios, wirelessLoaded = true) }
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
