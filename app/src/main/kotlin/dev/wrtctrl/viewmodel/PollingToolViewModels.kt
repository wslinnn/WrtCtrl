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
    /** 源代次：切 syslog↔dmesg 递增，在飞的旧源响应按此丢弃——generation 只管切设备，
     *  切源必须独立代次（旧源大文件慢返回会覆盖新源内容） */
    private var sourceGen = 0
    private val visible = MutableStateFlow(false)
    private val polling = MutableStateFlow(false)
    private var busy = false

    fun ensureLoaded(deviceId: String?) {
        if (deviceId == loadedDeviceId) return
        loadedDeviceId = deviceId
        generation++
        sourceGen++
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

    /** 切源：清列表立即拉。与在飞旧源请求并发安全：loadNow 启动时快照 source 与
     *  sourceGen，完成回写前校验——后启动的请求代次更新，旧源结果一律丢弃 */
    fun setSource(source: String) {
        if (_state.value.source == source) return
        sourceGen++
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
        val sgen = sourceGen
        val source = _state.value.source
        try {
            // 拉取+解析都在 IO；行数上限 2000 取尾
            val lines = withContext(Dispatchers.IO) {
                val arr = if (source == "syslog") WrtCore.readSyslog() else WrtCore.readDmesg()
                ToolParsers.parseLogLines(arr)
            }
            // 双代次校验：切设备（generation）或切源（sourceGen）后的旧响应一律丢弃
            if (gen != generation || sgen != sourceGen) return
            _state.update { current ->
                // 内容未变跳过实例替换：5s 全量重拉 99% 相同，省 LazyColumn 全量 diff
                if (current.lines == lines && !current.loading && !current.loadFailed) {
                    current
                } else {
                    current.copy(loading = false, loadFailed = false, lines = lines)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("wrtctrl", "syslog load failed: ${e.message}")
            if (gen == generation && sgen == sourceGen) {
                _state.update { it.copy(loading = false, loadFailed = true) }
            }
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
    /** DNS 反查单飞互斥（查询超时上限 > 轮询周期，重叠会反复作废在飞查询） */
    private var dnsBusy = false
    private val visible = MutableStateFlow(false)
    private val polling = MutableStateFlow(false)
    private var busy = false

    fun ensureLoaded(deviceId: String?) {
        if (deviceId == loadedDeviceId) return
        loadedDeviceId = deviceId
        generation++
        dnsSeq++ // 作废旧设备在飞的反查（dnsCache 合并前还会做 generation 校验，双保险）
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
            // 拉取与解析都在 IO：await 后的解析不再落回主线程
            val rowsResult = withContext(Dispatchers.IO) {
                WrtCore.callUbus("luci", "getConntrackList")
                    .optJSONArray("result")
                    ?.let(ToolParsers::parseConntrack)
            }
            val statsResult = withContext(Dispatchers.IO) {
                WrtCore.callUbus("luci", "getRealtimeStats", JSONObject().put("mode", "conntrack"))
                    .optJSONArray("result")
                    ?.let(ToolParsers::parseConntrackStats)
            }
            if (gen != generation) return
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("wrtctrl", "conntrack load failed: ${e.message}")
            if (gen == generation) _state.update { it.copy(loading = false, loadFailed = true) }
        } finally {
            busy = false
        }
    }

    /**
     * DNS 反查：增量查未缓存 IP（前 100 条连接的端点、单轮上限 100）；
     * 失败回落 IP 原样；异步执行不阻塞列表渲染。
     * 三重守卫：dnsBusy 互斥（单轮查询超时上限 5.5s > 5s 轮询，
     * 不互斥会反复作废在飞查询）；dnsSeq 丢弃过期轮次；generation 校验保证
     * 旧设备结果不并入新设备缓存。缓存有上限（conntrack 长期翻动会无界膨胀）。
     */
    private fun refreshDns() {
        if (!_state.value.dnsEnabled || dnsBusy) return
        val seq = ++dnsSeq
        val gen = generation
        dnsBusy = true
        viewModelScope.launch {
            try {
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
                if (seq != dnsSeq || gen != generation || map.isEmpty()) return@launch
                _state.update { it.copy(dnsCache = cappedCache(it.dnsCache + map)) }
            } finally {
                dnsBusy = false
            }
        }
    }

    /** 缓存上限：超出时按合入序丢弃最早条目（LinkedHashMap 序） */
    private fun cappedCache(cache: Map<String, String>): Map<String, String> {
        if (cache.size <= DNS_CACHE_MAX) return cache
        val trimmed = LinkedHashMap<String, String>(DNS_CACHE_MAX)
        cache.entries.drop(cache.size - DNS_CACHE_MAX).forEach { (k, v) -> trimmed[k] = v }
        return trimmed
    }

    private companion object {
        const val POLL_INTERVAL = 5000L
        const val DNS_LIMIT = 100
        const val DNS_TIMEOUT_MS = 2500

        /** DNS 反查缓存上限（单设备会话内） */
        const val DNS_CACHE_MAX = 512
    }
}
