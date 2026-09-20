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
import org.json.JSONArray
import org.json.JSONObject

data class ClientUiState(
    /** 无线 Tab 拉取中（v4/v6 走缓存无独立加载态） */
    val loading: Boolean = false,
    /** 下拉刷新指示（总时长 = max(拉取, 400ms)，见 holdRefreshSpin） */
    val refreshing: Boolean = false,
    val wirelessClients: List<WifiClient> = emptyList(),
    val dhcpv4: List<DhcpLease> = emptyList(),
    val dhcpv6: List<DhcpLease> = emptyList(),
    /** 无线拉取最近一次失败（列表为空时以失败文案区分「没有客户端」） */
    val loadFailed: Boolean = false,
)

/**
 * 客户端页状态：按 Tab 拉取。
 * 轮询：页面可见期间每 3s 静默刷新当前 Tab
 * （无线 Tab→接口+租约+assoclist，DHCP Tab→租约双栈），离开页面/后台暂停
 * （屏幕层 PollingGate 双门控）。
 * DHCP v4/v6 共享一次 getDHCPLeases 调用（失败不落缓存，成功才写 dhcpCache）；
 * 拉取失败静默保留旧值；切设备整页失效+ generation 守卫
 * 丢弃在飞的旧设备响应（含旧设备租约污染新设备缓存）。
 */
class ClientViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(ClientUiState())
    val state: StateFlow<ClientUiState> = _state

    private var loadedDeviceId: String? = null
    private var dhcpCache: Pair<List<DhcpLease>, List<DhcpLease>>? = null
    private var dhcpBusy = false

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
                // 挂起直至页面可见；先等一个周期再刷——进页的手动拉取不重复；
                // delay 期间门控可能关闭：拉取前复查，避免切走后多发一轮
                pollingActive.first { it }
                delay(POLL_INTERVAL)
                if (!pollingActive.value) continue
                if (polledTab == 0) loadWirelessNow(showLoading = false) else refreshDhcp()
            }
        }
    }

    /** 设备切换失效（含 DHCP 缓存）并重拉默认 Tab（无线终端） */
    fun ensureLoaded(deviceId: String?) {
        if (deviceId != loadedDeviceId) {
            loadedDeviceId = deviceId
            generation++
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
        val gen = generation
        if (showLoading) {
            _state.update { it.copy(loading = true) }
        }
        val radios = ubusSafe("luci-rpc", "getWirelessDevices")
        if (radios == null) {
            // 拉取失败：保留旧列表，仅置失败标志供空态分支
            if (gen == generation) _state.update { it.copy(loading = false, loadFailed = true) }
            return
        }
        val dhcp = ensureDhcp(gen)
        // 失败时 hostname 合并退化为无主机名（无线列表本身仍可展示）
        val hostnames = dhcp?.let { (v4, v6) -> ClientParsers.hostnameMap(v4, v6) } ?: emptyMap()
        val clients = mutableListOf<WifiClient>()
        ClientParsers.wifiIfaces(radios).forEach { (ifname, band) ->
            clients += ClientParsers.clientsOf(ifname, band, assocSafe(ifname), hostnames)
        }
        if (gen != generation) return
        _state.update {
            it.copy(
                loading = false,
                wirelessClients = clients,
                dhcpv4 = dhcp?.first ?: it.dhcpv4,
                dhcpv6 = dhcp?.second ?: it.dhcpv6,
                loadFailed = false,
            )
        }
    }

    /** DHCP Tab：有缓存零调用；在飞去重（旧 dhcpBusy 语义） */
    fun loadDhcp() {
        if (dhcpCache != null || dhcpBusy) return
        viewModelScope.launch {
            dhcpBusy = true
            try {
                val gen = generation
                ensureDhcp(gen)?.let { (v4, v6) ->
                    if (gen == generation) _state.update { it.copy(dhcpv4 = v4, dhcpv6 = v6) }
                }
            } finally {
                dhcpBusy = false
            }
        }
    }

    /** 下拉刷新：重拉当前 Tab（无线 Tab 连带刷新 DHCP 缓存），指示器总时长 = max(数据落地, 400ms) */
    fun refresh(tab: Int) {
        if (_state.value.refreshing) return
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            val startedAt = SystemClock.elapsedRealtime()
            dhcpCache = null
            if (tab == 0) loadWirelessNow(showLoading = false) else refreshDhcp()
            holdRefreshSpin(startedAt)
            _state.update { it.copy(refreshing = false) }
        }
    }

    /** DHCP Tab 静默强刷（轮询用）：绕过缓存取新租约并更新缓存与状态 */
    private suspend fun refreshDhcp() {
        dhcpCache = null
        val gen = generation
        val dhcp = ensureDhcp(gen) ?: return
        if (gen != generation) return
        _state.update { it.copy(dhcpv4 = dhcp.first, dhcpv6 = dhcp.second) }
    }

    /** DHCP 双栈租约（带缓存）；null=拉取失败——不落缓存（下次重试）、不覆盖旧值 */
    private suspend fun ensureDhcp(gen: Int): Pair<List<DhcpLease>, List<DhcpLease>>? {
        dhcpCache?.let { return it }
        val payload = ubusSafe("luci-rpc", "getDHCPLeases") ?: return null
        val pair = try {
            ClientParsers.dhcpLeases(payload)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("wrtctrl", "dhcp leases load failed: ${e.message}")
            return null
        }
        // 拉取期间设备已切换：结果不落缓存（旧设备租约会污染新设备的缓存）
        if (gen == generation) dhcpCache = pair
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
