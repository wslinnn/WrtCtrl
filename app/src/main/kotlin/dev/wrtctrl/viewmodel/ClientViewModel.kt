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
import org.json.JSONArray
import org.json.JSONObject

data class ClientUiState(
    /** 无线 Tab 拉取中（v4/v6 走缓存无独立加载态） */
    val loading: Boolean = false,
    /** 下拉刷新指示（最短 400ms） */
    val refreshing: Boolean = false,
    val wirelessClients: List<WifiClient> = emptyList(),
    val dhcpv4: List<DhcpLease> = emptyList(),
    val dhcpv6: List<DhcpLease> = emptyList(),
)

/**
 * 客户端页状态：按 Tab 拉取。
 * 轮询：页面可见期间每 3s 静默刷新当前 Tab
 * （无线 Tab→接口+租约+assoclist，DHCP Tab→租约双栈），离开页面/后台暂停。
 * DHCP v4/v6 共享一次 getDHCPLeases 调用（旧 dhcpCache/dhcpBusy 语义）；
 * 切设备整页失效；踢人走 core kickClient（hostapd del_client）。
 */
class ClientViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(ClientUiState())
    val state: StateFlow<ClientUiState> = _state

    private var loadedDeviceId: String? = null
    private var dhcpCache: Pair<List<DhcpLease>, List<DhcpLease>>? = null
    private var dhcpBusy = false

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
                if (polledTab == 0) loadWirelessNow(showLoading = false) else refreshDhcp()
            }
        }
    }

    /** 设备切换失效（含 DHCP 缓存）并重拉默认 Tab（无线终端） */
    fun ensureLoaded(deviceId: String?) {
        if (deviceId != loadedDeviceId) {
            loadedDeviceId = deviceId
            dhcpCache = null
            _state.update { ClientUiState() }
            loadWireless()
        }
    }

    /** 无线 Tab：接口清单 + DHCP（hostname 合并）+ 逐接口 assoclist 顺序拉；
     *  showLoading=false 为轮询静默刷新（不闪加载态） */
    fun loadWireless(showLoading: Boolean = true) {
        viewModelScope.launch { loadWirelessNow(showLoading) }
    }

    private suspend fun loadWirelessNow(showLoading: Boolean) {
        if (showLoading) {
            _state.update { it.copy(loading = true) }
        }
        val clients = mutableListOf<WifiClient>()
        try {
            val radios = ubusSafe("luci-rpc", "getWirelessDevices") ?: JSONObject()
            val (v4, v6) = ensureDhcp()
            val hostnames = ClientParsers.hostnameMap(v4, v6)
            ClientParsers.wifiIfaces(radios).forEach { (ifname, band) ->
                clients += ClientParsers.clientsOf(ifname, band, assocSafe(ifname), hostnames)
            }
            _state.update { it.copy(loading = false, wirelessClients = clients, dhcpv4 = v4, dhcpv6 = v6) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("wrtctrl", "wireless clients load failed: ${e.message}")
            _state.update { it.copy(loading = false) }
        }
    }

    /** DHCP Tab：有缓存零调用；在飞去重（旧 dhcpBusy 语义） */
    fun loadDhcp() {
        if (dhcpCache != null || dhcpBusy) return
        viewModelScope.launch {
            dhcpBusy = true
            try {
                val (v4, v6) = ensureDhcp()
                _state.update { it.copy(dhcpv4 = v4, dhcpv6 = v6) }
            } finally {
                dhcpBusy = false
            }
        }
    }

    /** 下拉刷新：重拉当前 Tab（无线 Tab 连带刷新 DHCP 缓存），指示器最短 400ms */
    fun refresh(tab: Int) {
        if (_state.value.refreshing) return
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            if (tab == 0) {
                dhcpCache = null
                loadWireless()
            } else {
                dhcpCache = null
                loadDhcp()
            }
            delay(400)
            _state.update { it.copy(refreshing = false) }
        }
    }

    /** DHCP Tab 静默强刷（轮询用）：绕过缓存取新租约并更新缓存与状态 */
    private suspend fun refreshDhcp() {
        dhcpCache = null
        val (v4, v6) = ensureDhcp()
        _state.update { it.copy(dhcpv4 = v4, dhcpv6 = v6) }
    }

    private suspend fun ensureDhcp(): Pair<List<DhcpLease>, List<DhcpLease>> {
        dhcpCache?.let { return it }
        val pair = try {
            val payload = ubusSafe("luci-rpc", "getDHCPLeases") ?: JSONObject()
            ClientParsers.dhcpLeases(payload)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("wrtctrl", "dhcp leases load failed: ${e.message}")
            emptyList<DhcpLease>() to emptyList()
        }
        dhcpCache = pair
        return pair
    }

    private suspend fun ubusSafe(objectName: String, method: String): JSONObject? = try {
        withContext(Dispatchers.IO) { WrtCore.callUbus(objectName, method) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.w("wrtctrl", "ubus $objectName.$method failed: ${e.message}")
        null
    }

    private suspend fun assocSafe(ifname: String): JSONArray = try {
        withContext(Dispatchers.IO) { WrtCore.assocList(ifname) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.w("wrtctrl", "assoclist $ifname failed: ${e.message}")
        JSONArray()
    }

    private companion object {
        /** 轮询周期：与首页一致（可见时静默刷新） */
        const val POLL_INTERVAL = 3000L
    }
}
