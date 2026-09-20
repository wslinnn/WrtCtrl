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
    /** 无线拉取中（v4/v6 走缓存无独立加载态） */
    val loading: Boolean = false,
    /** 下拉刷新指示（总时长 = max(拉取, 400ms)，见 holdRefreshSpin) */
    val refreshing: Boolean = false,
    val wirelessClients: List<WifiClient> = emptyList(),
    val dhcpv4: List<DhcpLease> = emptyList(),
    val dhcpv6: List<DhcpLease> = emptyList(),
    /** uci dhcp @host 静态租约 MAC 大写集合（拉取失败 = 空集，行全部按动态呈现） */
    val staticMacs: Set<String> = emptySet(),
    /** 无线拉取最近一次失败（列表为空时以失败文案区分「没有客户端」） */
    val loadFailed: Boolean = false,
    /** 租约首拉已落地（成功与否都算拉过；静默刷新失败不清此标志，保留旧值不闪态） */
    val leasesLoaded: Boolean = false,
    /** 租约首拉失败（仅在未落地时有意义：区分「暂无租约」与「加载失败」） */
    val leasesFailed: Boolean = false,
)

/**
 * 客户端页状态（两 Tab 合一屏）。
 * 轮询：页面可见期间每 3s 静默刷新——无线终端 + 租约双栈同拍；
 * 离开页面/后台暂停（屏幕层 PollingGate 双门控）。
 * DHCP v4/v6 共享一次 getDHCPLeases 调用（失败不落缓存，成功才写 dhcpCache）；
 * 静态租约表（uci get dhcp）拉一次缓存，失败下次重试；拉取失败静默保留旧值；
 * 切设备整页失效+ generation 守卫丢弃在飞的旧设备响应（含旧设备租约污染新设备缓存）。
 */
class ClientViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(ClientUiState())
    val state: StateFlow<ClientUiState> = _state

    private var loadedDeviceId: String? = null
    private var dhcpCache: Pair<List<DhcpLease>, List<DhcpLease>>? = null
    private var staticMacsCache: Set<String>? = null

    /** 设备代次（reqSeq 守卫）：切设备 +1，在飞响应按代次丢弃 */
    private var generation = 0

    /** 轮询开关（由屏幕层 PollingGate 驱动） */
    private val pollingActive = MutableStateFlow(false)

    fun setPollingActive(active: Boolean) {
        pollingActive.value = active
    }

    init {
        viewModelScope.launch {
            while (viewModelScope.isActive) {
                // 挂起直至页面可见；先等一个周期再刷——进页的手动拉取不重复；
                // delay 期间门控可能关闭：拉取前复查，避免切走后多发一轮
                pollingActive.first { it }
                delay(POLL_INTERVAL)
                if (!pollingActive.value) continue
                loadWirelessNow(showLoading = false)
                refreshDhcp()
            }
        }
    }

    /** 设备切换失效（含 DHCP/静态租约缓存）并重拉 */
    fun ensureLoaded(deviceId: String?) {
        if (deviceId != loadedDeviceId) {
            loadedDeviceId = deviceId
            generation++
            dhcpCache = null
            staticMacsCache = null
            _state.update { ClientUiState() }
            loadWireless()
        }
    }

    /** 无线终端 + DHCP（hostname/IP 合并）+ 逐接口 assoclist 顺序拉；
     *  showLoading=false 为轮询静默刷新（不闪加载态） */
    fun loadWireless(showLoading: Boolean = true) {
        viewModelScope.launch { loadWirelessNow(showLoading) }
    }

    private suspend fun loadWirelessNow(showLoading: Boolean) {
        val gen = generation
        if (showLoading) {
            _state.update { it.copy(loading = true) }
        }
        // 无线失败不连坐租约/静态表：radios 拉不到只置无线失败标志，租约照常拉取落地——
        // 否则无 wifi 固件上租约首拉要等第一个轮询 tick，「暂无租约」先行数秒（实测反馈）
        val radios = ubusSafe("luci-rpc", "getWirelessDevices")
        val dhcp = ensureDhcp(gen)
        val statics = ensureStatic(gen)
        // 失败时 hostname/IP 合并退化为无合并（无线列表本身仍可展示）
        val hostnames = dhcp?.let { (v4, v6) -> ClientParsers.hostnameMap(v4, v6) } ?: emptyMap()
        val ips = dhcp?.let { (v4, _) -> ClientParsers.ipMap(v4) } ?: emptyMap()
        val clients = mutableListOf<WifiClient>()
        radios?.let {
            ClientParsers.wifiIfaces(it).forEach { (ifname, band) ->
                clients += ClientParsers.clientsOf(ifname, band, assocSafe(ifname), hostnames, ips)
            }
        }
        if (gen != generation) return
        _state.update {
            it.copy(
                loading = false,
                wirelessClients = clients,
                dhcpv4 = dhcp?.first ?: it.dhcpv4,
                dhcpv6 = dhcp?.second ?: it.dhcpv6,
                staticMacs = statics ?: it.staticMacs,
                loadFailed = radios == null,
                leasesLoaded = dhcp != null || it.leasesLoaded,
                leasesFailed = dhcp == null && !it.leasesLoaded,
            )
        }
    }

    /** 下拉刷新：无线 + 租约 + 静态表全量重拉，指示器总时长 = max(数据落地, 400ms) */
    fun refresh() {
        if (_state.value.refreshing) return
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            val startedAt = SystemClock.elapsedRealtime()
            dhcpCache = null
            staticMacsCache = null
            loadWirelessNow(showLoading = false)
            refreshDhcp()
            holdRefreshSpin(startedAt)
            _state.update { it.copy(refreshing = false) }
        }
    }

    /** 静默强刷租约（轮询用）：绕过缓存取新租约并更新缓存与状态。
     *  失败静默保留旧值；仅在首拉尚未落地时置失败标志（区分「暂无租约」）。 */
    private suspend fun refreshDhcp() {
        dhcpCache = null
        val gen = generation
        val dhcp = ensureDhcp(gen) ?: run {
            if (gen == generation) {
                _state.update { if (it.leasesLoaded) it else it.copy(leasesFailed = true) }
            }
            return
        }
        if (gen != generation) return
        _state.update { it.copy(dhcpv4 = dhcp.first, dhcpv6 = dhcp.second, leasesLoaded = true, leasesFailed = false) }
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

    /** 静态租约 MAC 集合（WrtCore.uciGet 专用通道；拉一次缓存；失败返回 null 不缓存——
     *  下次轮询重试，不误标全部动态） */
    private suspend fun ensureStatic(gen: Int): Set<String>? {
        staticMacsCache?.let { return it }
        val uciDhcp = try {
            WrtCore.uciGet("dhcp")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("wrtctrl", "static dhcp hosts load failed: ${e.message}")
            return null
        }
        val macs = try {
            ClientParsers.staticHostMacs(uciDhcp)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("wrtctrl", "static dhcp hosts parse failed: ${e.message}")
            return null
        }
        if (gen == generation) staticMacsCache = macs
        return macs
    }

    private suspend fun ubusSafe(
        objectName: String,
        method: String,
        params: JSONObject = JSONObject(),
    ): JSONObject? = try {
        withContext(Dispatchers.IO) { WrtCore.callUbus(objectName, method, params) }
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
