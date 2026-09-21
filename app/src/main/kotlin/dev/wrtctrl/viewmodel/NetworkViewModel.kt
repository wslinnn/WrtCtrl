package dev.wrtctrl.viewmodel

import android.app.Application
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.wrtctrl.R
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
import kotlin.math.roundToLong

data class NetworkUiState(
    /** 首次拉取（进页到首批数据/失败落定前居中转圈） */
    val loading: Boolean = true,
    /** 下拉刷新指示（总时长 = max(拉取, 400ms)，见 holdRefreshSpin） */
    val refreshing: Boolean = false,
    val ifaces: List<IfaceInfo> = emptyList(),
    val deviceGroups: List<DeviceGroup> = emptyList(),
    val radios: List<RadioInfo> = emptyList(),
    /** 无线已成功拉取过（单屏后进页即拉，下拉刷新再拉） */
    val wirelessLoaded: Boolean = false,
    /** SSID 关联终端计数（ifname → assoclist 长度；失败项缺席不显示） */
    val assocCounts: Map<String, Int> = emptyMap(),
    /** SSID 凭据（ifname → secret；按需拉取，仅内存，切设备清空） */
    val wifiSecrets: Map<String, WifiSecret> = emptyMap(),
    /** 凭据拉取失败原因（键 = ifname；弹窗失败态展示 + 一键复制诊断；成功即移除） */
    val wifiSecretErrors: Map<String, String> = emptyMap(),
    /** wan 接口实时速率 B/s（rx to tx；无 wan/失败 = null 不显示该行） */
    val wanRates: Pair<Long, Long>? = null,
    /** 接口/设备最近一次拉取失败（列表为空时以失败文案区分「暂无数据」） */
    val loadFailed: Boolean = false,
    /** 无线最近一次拉取失败（首拉失败以失败文案区分「没有无线设备」） */
    val wirelessFailed: Boolean = false,
    /** radio 启停/重启写互斥（进行中的 radio section 名；组条开关转圈防重复写） */
    val busyRadio: String? = null,
    /** 一次性操作结果（失败 toast / 重启已下发提示；Screen 消费后清空） */
    @StringRes val opEventRes: Int? = null,
)

/**
 * 网络页状态（三 Tab 合一屏）：三个 ubus 调用 +
 * wan 实时速率（getRealtimeStats，wan 优先无则不拉）+ 逐 SSID assoclist 计数。
 * 轮询：页面可见期间每 3s 静默刷新全量，离开页面/后台暂停（PollingGate 双门控）。
 * 拉取失败静默保留旧值，仅置 failed 标志供空列表时显示失败文案；
 * 切设备整页失效重拉+ generation 守卫丢弃在飞的旧设备响应。
 *
 * TooManyFunctions：加载/轮询/凭据/radio 写序列是网络页职责全集（ClientViewModel/
 * UciPluginViewModel 同款类级豁免），拆分会把无线写与凭据语义打散。
 */
@Suppress("TooManyFunctions")
class NetworkViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(NetworkUiState())
    val state: StateFlow<NetworkUiState> = _state

    private var loadedDeviceId: String? = null

    /** 设备代次（reqSeq 守卫）：切设备 +1，在飞响应按代次丢弃 */
    private var generation = 0

    /** 轮询开关（由屏幕层 PollingGate 驱动） */
    private val pollingActive = MutableStateFlow(false)

    /** wan 接口的 l3_device（每次 dump 后更新；无 wan = null 不拉速率） */
    private var wanDevice: String? = null

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
                if (pollingActive.value) {
                    loadNow()
                    loadWirelessNow()
                    fetchWanRates()
                }
            }
        }
    }

    /** 设备切换失效重拉：单屏后无线一并失效重拉；同设备重复进入不重拉。
     *  接口/设备组一并清空回 loading 过渡态——与 ClientViewModel 全量重置同口径，
     *  否则切设备后短暂显示旧设备的接口卡 */
    fun ensureLoaded(deviceId: String?) {
        if (deviceId != loadedDeviceId) {
            loadedDeviceId = deviceId
            generation++
            wanDevice = null
            _state.update {
                it.copy(
                    loading = true,
                    ifaces = emptyList(),
                    deviceGroups = emptyList(),
                    radios = emptyList(),
                    wirelessLoaded = false,
                    wirelessFailed = false,
                    loadFailed = false,
                    assocCounts = emptyMap(),
                    wifiSecrets = emptyMap(),
                    wifiSecretErrors = emptyMap(),
                    wanRates = null,
                )
            }
            load()
            loadWireless()
        }
    }

    /** 接口 + 设备（调用 1+2） */
    fun load() {
        viewModelScope.launch { loadNow() }
    }

    /** 无线（调用 3 + assoclist 计数） */
    fun loadWireless() {
        if (_state.value.wirelessLoaded) return
        viewModelScope.launch { loadWirelessNow() }
    }

    /** 下拉刷新：全量调用，指示器总时长 = max(数据落地, 400ms) */
    fun refresh() {
        if (_state.value.refreshing) return
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true, wirelessLoaded = false) }
            val startedAt = SystemClock.elapsedRealtime()
            loadNow()
            loadWirelessNow()
            fetchWanRates()
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
        wanDevice = ifaces.firstOrNull { it.name == "wan" }?.l3Device
        _state.update {
            it.copy(loading = false, ifaces = ifaces, deviceGroups = groups, loadFailed = false)
        }
    }

    /** 无线：失败保留旧列表，仅置 wirelessFailed 供空态分支；成功后逐 ifname 拉 assoc 计数。
     *  并行补一次 uciGet("wireless") 取 radio 启停态（netifd status 不含 disabled option）；
     *  uci 读取失败保留 disabled=false 缺省（启停写路径权威，开关仍可用） */
    private suspend fun loadWirelessNow() {
        val gen = generation
        val payload = ubusSafe("luci-rpc", "getWirelessDevices")
        val uciWireless = uciWirelessSafe()
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
        val disabledMap = uciWireless?.let {
            try {
                NetworkParsers.radioDisabled(it)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("wrtctrl", "radio disabled parse failed: ${e.message}")
                emptyMap()
            }
        } ?: emptyMap()
        val merged = radios.map { radio -> radio.copy(disabled = disabledMap[radio.name] ?: false) }
        val counts = mutableMapOf<String, Int>()
        merged.flatMap { it.ifaces }.forEach { iface ->
            val ifname = iface.ifname
            if (ifname.isNotBlank()) assocCount(ifname)?.let { counts[ifname] = it }
        }
        if (gen != generation) return
        _state.update {
            it.copy(radios = merged, assocCounts = counts, wirelessLoaded = true, wirelessFailed = false)
        }
    }

    /**
     * radio 启停（高危确认后调用；setRadioEnabled 内含 disabled OR 同步 + commit + wifi up
     * 兜底——该写路径的首个实测验证点）。成功乐观置位 + 静默重拉校正；失败 toast + 重拉恢复真相。
     */
    fun setRadioEnabled(radioName: String, enabled: Boolean) {
        if (_state.value.busyRadio != null) return
        viewModelScope.launch {
            _state.update { it.copy(busyRadio = radioName) }
            val gen = generation
            val ok = try {
                withContext(Dispatchers.IO) { WrtCore.setRadioEnabled(radioName, enabled) }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("wrtctrl", "radio $radioName toggle failed: ${e.message}")
                false
            }
            if (gen != generation) return@launch
            if (ok) {
                _state.update { st ->
                    st.copy(
                        busyRadio = null,
                        radios = st.radios.map { if (it.name == radioName) it.copy(disabled = !enabled) else it },
                    )
                }
            } else {
                _state.update { it.copy(busyRadio = null, opEventRes = R.string.wifi_op_failed) }
            }
            loadWirelessNow()
        }
    }

    /** radio 重启（/sbin/wifi up，luci 同路径；不校验退出码，与 LuCI 一致的 best-effort 语义） */
    fun restartRadio(radioName: String) {
        if (_state.value.busyRadio != null) return
        viewModelScope.launch {
            _state.update { it.copy(busyRadio = radioName) }
            val gen = generation
            val ok = try {
                withContext(Dispatchers.IO) { WrtCore.restartRadio(radioName) }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("wrtctrl", "radio $radioName restart failed: ${e.message}")
                false
            }
            if (gen != generation) return@launch
            _state.update {
                it.copy(
                    busyRadio = null,
                    opEventRes = if (ok) R.string.wifi_restart_sent else R.string.wifi_op_failed,
                )
            }
            loadWirelessNow()
        }
    }

    fun consumeOpEvent() {
        _state.update { it.copy(opEventRes = null) }
    }

    /** 无线编辑器保存后的强刷入口（无视 wirelessLoaded 直接静默重拉） */
    fun refreshWireless() {
        viewModelScope.launch { loadWirelessNow() }
    }

    /** wan 实时速率（差分末值）：失败/无 wan 静默保留旧值语义——置 null 不显示该行 */
    private suspend fun fetchWanRates() {
        val device = wanDevice ?: return
        val gen = generation
        val payload = ubusSafe(
            "luci",
            "getRealtimeStats",
            JSONObject().put("mode", "interface").put("device", device),
        )
        val samples = payload?.optJSONArray("result") ?: JSONArray()
        val series = try {
            Format.bandwidthRates(samples)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("wrtctrl", "wan realtime stats failed: ${e.message}")
            null
        } ?: return
        val rx = series.rx.lastOrNull()?.roundToLong() ?: return
        val tx = series.tx.lastOrNull()?.roundToLong() ?: return
        if (gen != generation) return
        _state.update { it.copy(wanRates = rx to tx) }
    }

    /** SSID 关联终端数：失败 null（不计入 map，UI 缺席不显示） */
    private suspend fun assocCount(ifname: String): Int? = try {
        withContext(Dispatchers.IO) { WrtCore.assocList(ifname) }.length()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** 拉取 SSID 凭据（WrtCore.uciGet 类型化 section 表，仅内存态）。section ↔ 运行时 iface
     *  的关联与 LuCI wireless 语义一致：section 自带 ifname 精确匹配优先，否则 network 列表
     *  交集（且 section.device = 当前 radio）。失败置 wifiSecretFailed 供弹窗显失败态 +
     *  重试，原因进 logcat。ifname = 运行时接口名（弹窗/缓存键） */
    fun fetchWifiSecret(ifname: String) {
        viewModelScope.launch {
            val gen = generation
            val radio = state.value.radios.firstOrNull { r -> r.ifaces.any { it.ifname == ifname } }
            val iface = radio?.ifaces?.firstOrNull { it.ifname == ifname }
            if (radio == null || iface == null) {
                android.util.Log.w("wrtctrl", "wifi secret: iface $ifname not found in radios")
                markWifiSecretFailed(gen, ifname, "iface $ifname not found in getWirelessDevices")
                return@launch
            }
            val sections = try {
                WrtCore.uciGet("wireless")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = "uci get wireless failed: ${e.message ?: "unknown"}"
                android.util.Log.w("wrtctrl", "wifi secret: $reason")
                markWifiSecretFailed(gen, ifname, reason)
                return@launch
            }
            val secret = try {
                NetworkParsers.wifiSecret(sections, radio.name, ifname, iface.networks)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("wrtctrl", "wifi secret parse failed: ${e.message}")
                null
            }
            if (gen != generation) return@launch
            if (secret == null) {
                // 脱敏诊断：只带 device/ifname/network（绝不带 ssid/key），复制即可远程排障
                val dump = sections.keys().asSequence().mapNotNull { k ->
                    val s = sections.optJSONObject(k) ?: return@mapNotNull null
                    if (s.optString("section_type") != "wifi-iface") return@mapNotNull null
                    val o = s.optJSONObject("options")
                    val net = o?.optJSONArray("network")
                        ?.let { a -> (0 until a.length()).joinToString("/") { i -> a.optString(i) } }
                    "$k(device=${o?.optString("device")},ifname=${o?.optString("ifname")},network=$net)"
                }.joinToString("; ")
                val reason = "no section associated with $ifname (radio=${radio.name}, networks=${iface.networks}); " +
                    "wifi-iface sections: $dump"
                android.util.Log.w("wrtctrl", "wifi secret: $reason")
                markWifiSecretFailed(gen, ifname, reason)
            } else {
                _state.update {
                    it.copy(
                        wifiSecrets = it.wifiSecrets + (ifname to secret),
                        wifiSecretErrors = it.wifiSecretErrors - ifname,
                    )
                }
            }
        }
    }

    private fun markWifiSecretFailed(gen: Int, ifname: String, reason: String) {
        if (gen != generation) return
        _state.update { it.copy(wifiSecretErrors = it.wifiSecretErrors + (ifname to reason)) }
    }

    /** uci wireless 读取（radio 启停态源）；失败 null 静默（保留缺省 disabled=false，开关仍可用） */
    private suspend fun uciWirelessSafe(): JSONObject? = try {
        withContext(Dispatchers.IO) { WrtCore.uciGet("wireless") }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.w("wrtctrl", "uci wireless load failed: ${e.message}")
        null
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

    private companion object {
        /** 轮询周期：与首页一致（可见时静默刷新） */
        const val POLL_INTERVAL = 3000L
    }
}
