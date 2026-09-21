package dev.wrtctrl.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.wrtctrl.R
import dev.wrtctrl.bridge.WrtCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// ── 路由表（一次性 + 下拉刷新）──

data class RouteUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val rows: List<RouteRow> = emptyList(),
    /** null=正常；route_load_failed=exec 失败；route_parse_failed=双族都空（区分重试按钮语义） */
    val errorRes: Int? = null,
)

class RouteViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(RouteUiState())
    val state: StateFlow<RouteUiState> = _state

    private var loadedDeviceId: String? = null
    private var generation = 0
    /** 在飞互斥（与轮询工具页同语义）：首载进行中下拉刷新不并发双跑 */
    private var busy = false

    fun ensureLoaded(deviceId: String?) {
        if (deviceId == loadedDeviceId) return
        loadedDeviceId = deviceId
        generation++
        _state.value = RouteUiState()
        load()
    }

    fun load() {
        viewModelScope.launch { loadNow() }
    }

    /** 下拉刷新（总时长 = max(拉取, 400ms)） */
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

    private suspend fun loadNow() {
        busy = true
        val gen = generation
        try {
            _state.update { it.copy(errorRes = null) }
            // 双族并发；IPv6 失败（无 IPv6/ACL）容错不阻断 IPv4
            val r4 = execAsync("/sbin/ip", listOf("-4", "route", "show", "table", "all"))
            val r6 = try {
                execAsync("/sbin/ip", listOf("-6", "route", "show", "table", "all"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (gen != generation) return
            val rows = ToolParsers.parseRoutes(r4?.optString("stdout"), r6?.optString("stdout"))
            _state.update {
                it.copy(
                    loading = false,
                    rows = rows,
                    errorRes = if (rows.isEmpty()) R.string.route_parse_failed else null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("wrtctrl", "route table load failed: ${e.message}")
            if (gen == generation) _state.update { it.copy(loading = false, errorRes = R.string.route_load_failed) }
        } finally {
            busy = false
        }
    }

    private suspend fun execAsync(bin: String, args: List<String>): JSONObject = withContext(Dispatchers.IO) {
        val params = JSONArray().apply { args.forEach { put(it) } }
        WrtCore.callUbus(
            "file",
            "exec",
            JSONObject().put("command", bin).put("params", params).put("env", JSONObject.NULL),
            10000,
        )
    }
}

// ── 启动项（一次性 + 下拉刷新）──

data class StartupUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val rows: List<StartupRow> = emptyList(),
    val loadFailed: Boolean = false,
)

class StartupViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(StartupUiState())
    val state: StateFlow<StartupUiState> = _state

    private var loadedDeviceId: String? = null
    private var generation = 0
    /** 在飞互斥（与轮询工具页同语义）：首载进行中下拉刷新不并发双跑 */
    private var busy = false

    fun ensureLoaded(deviceId: String?) {
        if (deviceId == loadedDeviceId) return
        loadedDeviceId = deviceId
        generation++
        _state.value = StartupUiState()
        load()
    }

    fun load() {
        viewModelScope.launch { loadNow() }
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

    private suspend fun loadNow() {
        busy = true
        val gen = generation
        try {
            val data = withContext(Dispatchers.IO) { WrtCore.callUbus("rc", "list") }
            if (gen != generation) return
            _state.update {
                it.copy(loading = false, loadFailed = false, rows = ToolParsers.parseStartup(data))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("wrtctrl", "startup list load failed: ${e.message}")
            if (gen == generation) _state.update { it.copy(loading = false, loadFailed = true) }
        } finally {
            busy = false
        }
    }
}
