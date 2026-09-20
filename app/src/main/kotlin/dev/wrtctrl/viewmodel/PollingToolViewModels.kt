package dev.wrtctrl.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.wrtctrl.bridge.WrtCore
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
import org.json.JSONArray
import org.json.JSONObject

/**
 * 轮询工具页共享模式：
 * polling = 页面可见(PollingGate) × 自动刷新开关(autoRefresh 入 UiState，唯一事实源)
 * 合成流；delay-first（不与首轮重复）；busy 跳过（防 5s 轮询与 10s 超时倒挂堆积）；
 * 静默轮询失败保旧值；generation 守卫丢弃切设备后的在飞响应。
 */

// ── 系统进程 ──

data class ProcessUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val rows: List<ProcessRow> = emptyList(),
    val loadFailed: Boolean = false,
    /** 展开详情的 PID 集合（跨静默刷新保留） */
    val expanded: Set<String> = emptySet(),
    val autoRefresh: Boolean = true,
)

class ProcessViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(ProcessUiState())
    val state: StateFlow<ProcessUiState> = _state

    private var loadedDeviceId: String? = null
    private var generation = 0
    private val visible = MutableStateFlow(false)
    private val polling = MutableStateFlow(false)
    private var busy = false

    fun ensureLoaded(deviceId: String?) {
        if (deviceId == loadedDeviceId) return
        loadedDeviceId = deviceId
        generation++
        _state.value = ProcessUiState()
        load()
    }

    fun setVisible(v: Boolean) {
        visible.value = v
        polling.value = v && _state.value.autoRefresh
    }

    fun setAutoRefresh(v: Boolean) {
        _state.update { it.copy(autoRefresh = v) }
        polling.value = v && visible.value
    }

    fun toggleExpand(pid: String) {
        _state.update { it.copy(expanded = if (pid in it.expanded) it.expanded - pid else it.expanded + pid) }
    }

    fun refresh() {
        if (busy || _state.value.refreshing) return
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            val startedAt = android.os.SystemClock.elapsedRealtime()
            loadNow()
            holdRefreshSpin(startedAt)
            _state.update { it.copy(refreshing = false) }
        }
    }

    init {
        viewModelScope.launch {
            while (viewModelScope.isActive) {
                polling.first { it }
                delay(POLL_INTERVAL)
                // delay 期间门控可能关闭：拉取前复查，避免关停后多刷一次
                if (polling.value && !busy) loadNow()
            }
        }
    }

    private fun load() {
        viewModelScope.launch { loadNow() }
    }

    private suspend fun loadNow() {
        busy = true
        val gen = generation
        try {
            val data = withContext(Dispatchers.IO) { WrtCore.callUbus("luci", "getProcessList") }
            val rows = data.optJSONArray("result")?.let(ToolParsers::parseProcesses) ?: emptyList()
            if (gen != generation) return
            _state.update { it.copy(loading = false, loadFailed = false, rows = rows) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("wrtctrl", "process list load failed: ${e.message}")
            // 静默保留旧列表，仅置失败标志供空态分支
            if (gen == generation) _state.update { it.copy(loading = false, loadFailed = true) }
        } finally {
            busy = false
        }
    }

    private companion object {
        const val POLL_INTERVAL = 5000L
    }
}

// ── 系统日志（syslog/dmesg 双源）──

data class SyslogUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val source: String = "syslog",
    val lines: List<LogLineUi> = emptyList(),
    val loadFailed: Boolean = false,
    val autoRefresh: Boolean = true,
)

class SyslogViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(SyslogUiState())
    val state: StateFlow<SyslogUiState> = _state

    private var loadedDeviceId: String? = null
    private var generation = 0
    private val visible = MutableStateFlow(false)
    private val polling = MutableStateFlow(false)
    private var busy = false

    fun ensureLoaded(deviceId: String?) {
        if (deviceId == loadedDeviceId) return
        loadedDeviceId = deviceId
        generation++
        _state.value = SyslogUiState()
        load()
    }

    fun setVisible(v: Boolean) {
        visible.value = v
        polling.value = v && _state.value.autoRefresh
    }

    fun setAutoRefresh(v: Boolean) {
        _state.update { it.copy(autoRefresh = v) }
        polling.value = v && visible.value
    }

    /** 切源：清列表立即拉（fetch 用切换后的源；busy 由调用时序天然规避——切源时旧请求结果按 gen 丢弃） */
    fun setSource(source: String) {
        if (_state.value.source == source) return
        _state.update { it.copy(source = source, lines = emptyList(), loadFailed = false) }
        load()
    }

    fun refresh() {
        if (busy || _state.value.refreshing) return
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            val startedAt = android.os.SystemClock.elapsedRealtime()
            loadNow()
            holdRefreshSpin(startedAt)
            _state.update { it.copy(refreshing = false) }
        }
    }

    init {
        viewModelScope.launch {
            while (viewModelScope.isActive) {
                polling.first { it }
                delay(POLL_INTERVAL)
                // delay 期间门控可能关闭：拉取前复查，避免关停后多刷一次
                if (polling.value && !busy) loadNow()
            }
        }
    }

    private fun load() {
        viewModelScope.launch { loadNow() }
    }

    private suspend fun loadNow() {
        busy = true
        val gen = generation
        val source = _state.value.source
        try {
            val arr = withContext(Dispatchers.IO) {
                if (source == "syslog") WrtCore.readSyslog() else WrtCore.readDmesg()
            }
            if (gen != generation) return
            _state.update {
                it.copy(loading = false, loadFailed = false, lines = ToolParsers.parseLogLines(arr))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("wrtctrl", "syslog load failed: ${e.message}")
            if (gen == generation) _state.update { it.copy(loading = false, loadFailed = true) }
        } finally {
            busy = false
        }
    }

    private companion object {
        const val POLL_INTERVAL = 5000L
    }
}

// ── 活动连接（DNS 反查保留）──

data class ConntrackUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val rows: List<ConnRow> = emptyList(),
    val total: Int = 0,
    val udp: WindowStats? = null,
    val tcp: WindowStats? = null,
    val other: WindowStats? = null,
    val loadFailed: Boolean = false,
    /** DNS 反查开关（默认关）与反查缓存（ip→hostname）；autoRefresh 同为唯一事实源 */
    val dnsEnabled: Boolean = false,
    val dnsCache: Map<String, String> = emptyMap(),
    val autoRefresh: Boolean = true,
)

class ConntrackViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(ConntrackUiState())
    val state: StateFlow<ConntrackUiState> = _state

    private var loadedDeviceId: String? = null
    private var generation = 0
    private var dnsSeq = 0
    private val visible = MutableStateFlow(false)
    private val polling = MutableStateFlow(false)
    private var busy = false

    fun ensureLoaded(deviceId: String?) {
        if (deviceId == loadedDeviceId) return
        loadedDeviceId = deviceId
        generation++
        _state.value = ConntrackUiState()
        load()
    }

    fun setVisible(v: Boolean) {
        visible.value = v
        polling.value = v && _state.value.autoRefresh
    }

    fun setAutoRefresh(v: Boolean) {
        _state.update { it.copy(autoRefresh = v) }
        polling.value = v && visible.value
    }

    fun setDnsEnabled(on: Boolean) {
        _state.update { it.copy(dnsEnabled = on) }
        if (on) refreshDns()
    }

    fun refresh() {
        if (busy || _state.value.refreshing) return
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            val startedAt = android.os.SystemClock.elapsedRealtime()
            loadNow()
            holdRefreshSpin(startedAt)
            _state.update { it.copy(refreshing = false) }
        }
    }

    init {
        viewModelScope.launch {
            while (viewModelScope.isActive) {
                polling.first { it }
                delay(POLL_INTERVAL)
                // delay 期间门控可能关闭：拉取前复查，避免关停后多刷一次
                if (polling.value && !busy) loadNow()
            }
        }
    }

    private fun load() {
        viewModelScope.launch { loadNow() }
    }

    private suspend fun loadNow() {
        busy = true
        val gen = generation
        try {
            coroutineScope {
                val list = async(Dispatchers.IO) { WrtCore.callUbus("luci", "getConntrackList") }
                val stats = async(Dispatchers.IO) {
                    WrtCore.callUbus("luci", "getRealtimeStats", JSONObject().put("mode", "conntrack"))
                }
                val rowsResult = list.await().optJSONArray("result")?.let(ToolParsers::parseConntrack)
                val statsResult = stats.await().optJSONArray("result")?.let(ToolParsers::parseConntrackStats)
                if (gen != generation) return@coroutineScope
                _state.update {
                    it.copy(
                        loading = false,
                        loadFailed = false,
                        rows = rowsResult?.second ?: it.rows,
                        total = rowsResult?.first ?: it.total,
                        udp = statsResult?.first,
                        tcp = statsResult?.second,
                        other = statsResult?.third,
                    )
                }
                refreshDns()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("wrtctrl", "conntrack load failed: ${e.message}")
            if (gen == generation) _state.update { it.copy(loading = false, loadFailed = true) }
        } finally {
            busy = false
        }
    }

    /** DNS 反查：增量查未缓存 IP（前 100 条连接的端点、单轮上限 100）；
     *  失败回落 IP 原样；dnsSeq 丢弃过期轮次。异步执行不阻塞列表渲染。 */
    private fun refreshDns() {
        if (!_state.value.dnsEnabled) return
        val seq = ++dnsSeq
        viewModelScope.launch {
            val ips = _state.value.rows.take(DNS_LIMIT)
                .flatMap { sequenceOf(it.src, it.dst) }
                .filter { it.isNotBlank() }
                .distinct()
            val todo = ips.filterNot { _state.value.dnsCache.containsKey(it) }.take(DNS_LIMIT)
            if (todo.isEmpty()) return@launch
            val map = try {
                withContext(Dispatchers.IO) {
                    val addrs = JSONArray().apply { todo.forEach { put(it) } }
                    WrtCore.callUbus(
                        "network.rrdns",
                        "lookup",
                        JSONObject().put("addrs", addrs).put("timeout", DNS_TIMEOUT_MS).put("limit", DNS_LIMIT),
                        DNS_TIMEOUT_MS + 3000,
                    )
                }.let(ToolParsers::parseRrdns)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("wrtctrl", "rrdns lookup failed: ${e.message}")
                return@launch
            }
            if (seq != dnsSeq || map.isEmpty()) return@launch
            _state.update { it.copy(dnsCache = it.dnsCache + map) }
        }
    }

    private companion object {
        const val POLL_INTERVAL = 5000L
        const val DNS_LIMIT = 100
        const val DNS_TIMEOUT_MS = 2500
    }
}
