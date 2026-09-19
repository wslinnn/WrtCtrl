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
import kotlinx.coroutines.flow.update
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
 * 网络页状态：三个 ubus 调用，无轮询。
 * 拉取失败静默落空态（错误链只进 logcat）；
 * 切设备后 ensureLoaded 触发整页重拉（缓存清空惯例）。
 */
class NetworkViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(NetworkUiState())
    val state: StateFlow<NetworkUiState> = _state

    private var loadedDeviceId: String? = null

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
}
