package dev.wrtctrl.viewmodel

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.wrtctrl.R
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
    /** uci dhcp @host 静态租约全量（拉取失败 = 空表，行全部按动态呈现） */
    val staticHosts: List<StaticHost> = emptyList(),
    /** 无线拉取最近一次失败（列表为空时以失败文案区分「没有客户端」） */
    val loadFailed: Boolean = false,
    /** 租约首拉已落地（成功与否都算拉过；静默刷新失败不清此标志，保留旧值不闪态） */
    val leasesLoaded: Boolean = false,
    /** 租约首拉失败（仅在未落地时有意义：区分「暂无租约」与「加载失败」） */
    val leasesFailed: Boolean = false,
    /** 拉黑规则：归一化 MAC（小写）→ uci section 名（来源 = firewall 配置真实规则） */
    val blockedMacs: Map<String, String> = emptyMap(),
    /** 写操作进行中的 MAC（小写域；同一 MAC 的写动作互斥——动作行转圈禁点） */
    val busyMac: String? = null,
    /** 写失败分类文案（一次性事件，Screen 呈现后 consumeWriteError 清除） */
    val writeError: String? = null,
)

/**
 * 客户端页状态（两 Tab 合一屏）。
 * 轮询：页面可见期间每 3s 静默刷新——无线终端 + 租约双栈同拍；
 * 离开页面/后台暂停（屏幕层 PollingGate 双门控）。
 * DHCP v4/v6 共享一次 getDHCPLeases 调用（失败不落缓存，成功才写 dhcpCache）；
 * 静态租约表（uci get dhcp）拉一次缓存，失败下次重试；拉取失败静默保留旧值；
 * 切设备整页失效+ generation 守卫丢弃在飞的旧设备响应（含旧设备租约污染新设备缓存）。
 */
// TooManyFunctions：动作集 = 读（无线/租约/静态/拉黑四源）+ 写（拉黑/静态两对），函数数由
// 功能面决定，拆 VM 只会制造跨类状态同步
@Suppress("TooManyFunctions")
class ClientViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(ClientUiState())
    val state: StateFlow<ClientUiState> = _state

    private var loadedDeviceId: String? = null
    private var dhcpCache: Pair<List<DhcpLease>, List<DhcpLease>>? = null
    private var staticHostsCache: List<StaticHost>? = null
    private var blockedCache: Map<String, String>? = null

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

    /** 设备切换失效（含 DHCP/静态租约/拉黑缓存）并重拉 */
    fun ensureLoaded(deviceId: String?) {
        if (deviceId != loadedDeviceId) {
            loadedDeviceId = deviceId
            generation++
            dhcpCache = null
            staticHostsCache = null
            blockedCache = null
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
        val blocked = ensureBlocked(gen)
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
                staticHosts = statics ?: it.staticHosts,
                blockedMacs = blocked ?: it.blockedMacs,
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
            staticHostsCache = null
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

    /** 静态租约表（WrtCore.uciGet 专用通道；拉一次缓存；失败返回 null 不缓存——
     *  下次轮询重试，不误标全部动态） */
    private suspend fun ensureStatic(gen: Int): List<StaticHost>? {
        staticHostsCache?.let { return it }
        val uciDhcp = try {
            WrtCore.uciGet("dhcp")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("wrtctrl", "static dhcp hosts load failed: ${e.message}")
            return null
        }
        val hosts = try {
            ClientParsers.staticHosts(uciDhcp)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("wrtctrl", "static dhcp hosts parse failed: ${e.message}")
            return null
        }
        if (gen == generation) staticHostsCache = hosts
        return hosts
    }

    /** 拉黑规则集合（WrtCore.uciGet("firewall") 专用通道；拉一次缓存；失败返回 null 不缓存——
     *  下次轮询重试，静默；写操作成功后主动失效缓存，下一拍对账设备端真实规则） */
    private suspend fun ensureBlocked(gen: Int): Map<String, String>? {
        blockedCache?.let { return it }
        val uciFirewall = try {
            WrtCore.uciGet("firewall")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("wrtctrl", "firewall config load failed: ${e.message}")
            return null
        }
        val macs = try {
            ClientParsers.blockedMacs(uciFirewall)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("wrtctrl", "firewall config parse failed: ${e.message}")
            return null
        }
        if (gen == generation) blockedCache = macs
        return macs
    }

    /** 写成功收尾：本地即时更新（不等下拍轮询）+ busy 清除；gen 失配只清 busy（防旧设备操作污染新设备界面） */
    private fun finishWrite(gen: Int, transform: (ClientUiState) -> ClientUiState) {
        _state.update { current ->
            (if (gen == generation) transform(current) else current).copy(busyMac = null)
        }
    }

    /** 写失败收尾：双缓存失效（下拍对账设备端真实状态）+ busy 清除 + 分类文案（原始链已进 logcat） */
    private fun writeFailed(gen: Int, e: Exception, textRes: Int) {
        android.util.Log.w("wrtctrl", "write failed: ${e.message}")
        staticHostsCache = null
        blockedCache = null
        _state.update {
            it.copy(
                busyMac = null,
                writeError = if (gen == generation) getApplication<Application>().getString(textRes) else null,
            )
        }
    }

    private fun writeErrorText(textRes: Int): String = getApplication<Application>().getString(textRes)

    /**
     * 拉黑 / 解除拉黑（防重复提交）：
     * 拉黑 = uciAdd → uciSet（name=block_<mac小写>，src=lan→dest=wan，src_mac，proto=all，
     * target=REJECT）→ uciCommit（core 安全提交 = session 预检 + apply{rollback} + confirm，
     * apply 内部已 commit + firewall reload）；解除 = uciDelete 该 section → uciCommit。
     * 成功本地即时更新集合（不等下拍轮询），并失效缓存对账；失败 Toast 分类文案 + logcat。
     */
    fun toggleBlock(macRaw: String, block: Boolean) {
        val mac = macRaw.lowercase()
        // 防重 + 弹窗期间轮询翻转竞态：以当前集合状态为准，已达标直接跳过
        if (_state.value.busyMac != null || (mac in _state.value.blockedMacs) == block) return
        viewModelScope.launch {
            val gen = generation
            _state.update { it.copy(busyMac = mac) }
            try {
                if (block) {
                    val section = WrtCore.uciAdd("firewall", "rule")
                    WrtCore.uciSet(
                        "firewall",
                        section,
                        JSONObject().apply {
                            put("name", ClientParsers.BLOCK_RULE_PREFIX + mac)
                            put("src", "lan")
                            put("dest", "wan")
                            put("src_mac", mac)
                            put("proto", "all")
                            put("target", "REJECT")
                        },
                    )
                    WrtCore.uciCommit("firewall")
                    blockedCache = null
                    finishWrite(gen) { it.copy(blockedMacs = it.blockedMacs + (mac to section)) }
                } else {
                    // 集合里没有（如读取失败期间点解除）：不盲删，回读设备端定位后再删
                    val section = _state.value.blockedMacs[mac]
                        ?: ensureBlocked(gen)?.get(mac)
                    if (section == null) {
                        _state.update {
                            it.copy(
                                busyMac = null,
                                writeError = if (gen == generation) writeErrorText(R.string.client_block_failed) else null,
                            )
                        }
                        return@launch
                    }
                    WrtCore.uciDelete("firewall", section)
                    WrtCore.uciCommit("firewall")
                    blockedCache = null
                    finishWrite(gen) { it.copy(blockedMacs = it.blockedMacs - mac) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                writeFailed(gen, e, R.string.client_block_failed)
            }
        }
    }

    /**
     * 一键静态绑定（防重复提交）：当前 IP 直接绑定 MAC
     * （name 自动带入 hostname，空不写——无用户输入、无注入面）；uciAdd("dhcp","host") →
     * uciSet{mac,ip,name?} → uciCommit("dhcp")（apply 触发 dnsmasq reload；设备在下次续租/重连
     * 时才切换到绑定 IP——确认弹窗须写明）。
     */
    fun bindStatic(macRaw: String, ip: String, name: String?) {
        val mac = macRaw.uppercase()
        if (_state.value.busyMac != null || _state.value.staticHosts.any { it.mac == mac }) return
        viewModelScope.launch {
            val gen = generation
            _state.update { it.copy(busyMac = mac.lowercase()) }
            try {
                val section = WrtCore.uciAdd("dhcp", "host")
                WrtCore.uciSet(
                    "dhcp",
                    section,
                    JSONObject().apply {
                        put("mac", mac)
                        put("ip", ip)
                        if (!name.isNullOrBlank()) put("name", name)
                    },
                )
                WrtCore.uciCommit("dhcp")
                staticHostsCache = null
                finishWrite(gen) { current ->
                    current.copy(
                        staticHosts = (current.staticHosts + StaticHost(section, mac, name?.takeIf(String::isNotBlank), ip))
                            .sortedBy { it.mac },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                writeFailed(gen, e, R.string.client_static_failed)
            }
        }
    }

    /** 取消静态绑定：删除该 MAC 的 @host section（本地集合定位，缺失回读设备端，不盲删），恢复动态分配 */
    fun unbindStatic(macRaw: String) {
        val mac = macRaw.uppercase()
        if (_state.value.busyMac != null || _state.value.staticHosts.none { it.mac == mac }) return
        viewModelScope.launch {
            val gen = generation
            _state.update { it.copy(busyMac = mac.lowercase()) }
            try {
                val section = _state.value.staticHosts.firstOrNull { it.mac == mac }?.section
                    ?: ensureStatic(gen)?.firstOrNull { it.mac == mac }?.section
                if (section == null) {
                    _state.update {
                        it.copy(
                            busyMac = null,
                            writeError = if (gen == generation) writeErrorText(R.string.client_static_failed) else null,
                        )
                    }
                    return@launch
                }
                WrtCore.uciDelete("dhcp", section)
                WrtCore.uciCommit("dhcp")
                staticHostsCache = null
                finishWrite(gen) { it.copy(staticHosts = it.staticHosts.filter { host -> host.mac != mac }) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                writeFailed(gen, e, R.string.client_static_failed)
            }
        }
    }

    /** Screen 呈现失败 Toast 后回调清除（一次性事件） */
    fun consumeWriteError() {
        _state.update { if (it.writeError == null) it else it.copy(writeError = null) }
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
