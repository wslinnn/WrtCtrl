package dev.wrtctrl.viewmodel.plugin

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.wrtctrl.R
import dev.wrtctrl.bridge.WrtCore
import dev.wrtctrl.viewmodel.holdRefreshSpin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 通用 uci 插件列表状态（M4；轮询例外见 UpnpViewModel） */
data class UciListUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    /** 按 section 名排序的设备条目（org.json keys() HashMap 序纪律） */
    val entries: List<UciEntry> = emptyList(),
    /** 首拉失败（空 entries 时显示错误卡 + 重试；有意偏离旧 toast+空列表） */
    val loadFailed: Boolean = false,
    /** 行级写互斥：开关进行中的 section */
    val busySection: String? = null,
    /** 编辑器保存/删除互斥 */
    val saving: Boolean = false,
    /** 一次性写失败文案（Screen Toast 后 consume） */
    @StringRes val writeErrorRes: Int? = null,
)

/**
 * uci 插件页基类（与 LuCI uci rpc 序列一致）：
 * 列表 = uciGet(config) 按类型过滤；写 = set/add/delete → uciCommit（core 安全提交 =
 * session 预检 + apply{rollback:120} + confirm + commit_lock 串行）→ best-effort init.d
 * reload（失败不阻断，配置已落盘）；行内开关乐观幂等 upsert（同 section 整条替换，
 * 静态租约 key 重复崩溃教训）；generation 守卫丢切设备在飞响应。
 *
 * TooManyFunctions：列表加载/候选/写序列/一次性事件消费是基类职责全集（ClientViewModel 先例），
 * 拆分会把写序列语义打散到多个协作类。
 */
@Suppress("TooManyFunctions")
abstract class UciPluginViewModel(
    application: Application,
    /** uci config 名（cifs-mount / usb_printer 等带连字符/下划线的原样） */
    val config: String,
    /** 保存/删除后 best-effort reload 的 init 脚本；null = 不 apply */
    private val initScript: String?,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(UciListUiState())
    val state: StateFlow<UciListUiState> = _state

    /** kind → 候选表（protected 供子类补写，如 usb-printer 的 detectlp 回填） */
    protected val candidateCache = MutableStateFlow<Map<String, List<UciOption>>>(emptyMap())
    val candidates: StateFlow<Map<String, List<UciOption>>> = candidateCache

    /** 一次性操作成功事件（保存/删除成功 toast，与 LuCI 行为一致；Screen 提示后 consume） */
    private val _doneEvent = MutableStateFlow<Int?>(null)
    val doneEvent: StateFlow<Int?> = _doneEvent

    fun consumeDoneEvent() {
        _doneEvent.value = null
    }

    private var loadedDeviceId: String? = null
    protected var generation = 0
        private set

    /** 需要的候选类别（子类声明；hosthints 两键一次拉取双份回填） */
    protected open fun candidateKinds(): List<UciCandidates> = emptyList()

    /** 本页列表展示的 section 类型；null = 全量（firewall/samba4/upnp 多类型自分流） */
    protected open fun sectionType(): String? = null

    fun ensureLoaded(deviceId: String?) {
        if (deviceId == loadedDeviceId) return
        loadedDeviceId = deviceId
        generation++
        _state.value = UciListUiState()
        load()
    }

    fun refresh() {
        if (_state.value.loading || _state.value.refreshing) return
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            val startedAt = android.os.SystemClock.elapsedRealtime()
            loadNow()
            holdRefreshSpin(startedAt)
            _state.update { it.copy(refreshing = false) }
        }
    }

    fun retry() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true) }
        load()
    }

    fun consumeWriteError() {
        _state.update { it.copy(writeErrorRes = null) }
    }

    protected fun load() {
        viewModelScope.launch { loadNow() }
    }

    private suspend fun loadNow() {
        val gen = generation
        try {
            val data = withContext(Dispatchers.IO) { WrtCore.uciGet(config) }
            if (gen != generation) return
            _state.update {
                it.copy(loading = false, loadFailed = false, entries = UciParsers.entries(data, sectionType()))
            }
            loadCandidates()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("wrtctrl", "uci $config load failed: ${e.message}")
            if (gen == generation) _state.update { it.copy(loading = false, loadFailed = true) }
        }
    }

    private suspend fun loadCandidates() {
        val kinds = candidateKinds().distinct()
        if (kinds.isEmpty()) return
        val gen = generation
        val map = HashMap<String, List<UciOption>>()
        coroutineScope {
            kinds.map { kind ->
                async(Dispatchers.IO) {
                    try {
                        when (kind) {
                            UciCandidates.HOSTHINTS_IP, UciCandidates.HOSTHINTS_MAC ->
                                UciParsers.hostHints(withContext(Dispatchers.IO) { WrtCore.hostHints() })
                            else -> mapOf(
                                kind.kind to UciParsers.candidates(
                                    withContext(Dispatchers.IO) { WrtCore.candidates(bridgeKind(kind)) },
                                ),
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // 候选缺失退化为手填（与 LuCI 行为一致），不阻塞列表；logcat 留痕供排障
                        android.util.Log.w("wrtctrl", "candidates ${kind.kind} failed: ${e.message}")
                        emptyMap()
                    }
                }
            }.forEach { map.putAll(it.await()) }
        }
        if (gen == generation) candidateCache.value = map
    }

    private fun bridgeKind(kind: UciCandidates): String = when (kind) {
        UciCandidates.HOSTHINTS_IP, UciCandidates.HOSTHINTS_MAC -> "hosthints"
        UciCandidates.DEVICES -> "devices"
        UciCandidates.INTERFACES -> "interfaces"
        UciCandidates.ZONES -> "zones"
        UciCandidates.PRINTERS -> "printers"
        UciCandidates.HELPERS -> "helpers"
        UciCandidates.IPSETS -> "ipsets"
        UciCandidates.IFADDRS -> "ifaddrs"
    }

    /** 写互斥单飞 + CancellationException 透传；失败 false（错误链进 logcat，UI 走分类文案） */
    private suspend fun runWrite(block: suspend () -> Unit): Boolean = try {
        withContext(Dispatchers.IO) { block() }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.w("wrtctrl", "uci $config write failed: ${e.message}")
        false
    }

    private suspend fun applyBestEffort() {
        if (initScript == null) return
        try {
            withContext(Dispatchers.IO) { WrtCore.apply(initScript) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // apply 失败不阻断：配置已 commit 落盘，下次 reload 生效（与 LuCI 行为一致）
            android.util.Log.w("wrtctrl", "apply $initScript failed: ${e.message}")
        }
    }

    /** 行内开关直写：set→commit→apply；成功乐观替换同 section（幂等），失败回读设备 */
    fun toggle(entry: UciEntry, values: JSONObject) {
        if (_state.value.busySection != null || _state.value.saving) return
        viewModelScope.launch {
            _state.update { it.copy(busySection = entry.section) }
            val gen = generation
            val ok = runWrite {
                WrtCore.uciSet(config, entry.section, values)
                WrtCore.uciCommit(config)
            }
            if (ok) applyBestEffort()
            if (gen != generation) return@launch
            if (ok) {
                _state.update { st ->
                    st.copy(
                        busySection = null,
                        entries = st.entries.map {
                            if (it.section == entry.section) it.withPatch(values) else it
                        },
                    )
                }
            } else {
                _state.update { it.copy(busySection = null, writeErrorRes = R.string.plugin_save_failed) }
                load()
            }
        }
    }

    /**
     * 编辑器保存：新建 = add→set(有值)→commit；编辑 = set→commit；随后 best-effort apply。
     * onResult 主线程回调（成功后 Screen 关编辑页并提示）。
     */
    fun submit(
        create: Boolean,
        sectionType: String,
        editSection: String?,
        values: JSONObject,
        onResult: (Boolean) -> Unit,
    ) {
        if (_state.value.saving || _state.value.busySection != null) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            val gen = generation
            val ok = runWrite {
                if (create) {
                    // rpcd uci.add 只收 config+type（带 values 触发 INVALID_ARGS[2]，与 LuCI 一致）
                    val name = WrtCore.uciAdd(config, sectionType)
                    if (values.length() > 0) WrtCore.uciSet(config, name, values)
                } else {
                    WrtCore.uciSet(config, editSection!!, values)
                }
                WrtCore.uciCommit(config)
            }
            if (ok) applyBestEffort()
            if (gen != generation) {
                onResult(false)
                return@launch
            }
            _state.update { it.copy(saving = false) }
            if (ok) {
                load()
                _doneEvent.value = R.string.plugin_save_success
                onResult(true)
            } else {
                _state.update { it.copy(writeErrorRes = R.string.plugin_save_failed) }
                onResult(false)
            }
        }
    }

    fun remove(section: String, onResult: (Boolean) -> Unit) {
        if (_state.value.saving || _state.value.busySection != null) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            val gen = generation
            val ok = runWrite {
                WrtCore.uciDelete(config, section)
                WrtCore.uciCommit(config)
            }
            if (ok) applyBestEffort()
            if (gen != generation) {
                onResult(false)
                return@launch
            }
            _state.update { it.copy(saving = false) }
            if (ok) {
                load()
                _doneEvent.value = R.string.plugin_delete_success
                onResult(true)
            } else {
                _state.update { it.copy(writeErrorRes = R.string.plugin_delete_failed) }
                onResult(false)
            }
        }
    }
}

/** JSONObject 写补丁并入条目 options（toggle 乐观更新用；幂等整键替换） */
fun UciEntry.withPatch(values: JSONObject): UciEntry {
    val patch = HashMap<String, List<String>>()
    val keys = values.keys()
    while (keys.hasNext()) {
        val k = keys.next()
        val arr = values.optJSONArray(k)
        patch[k] = if (arr != null) {
            List(arr.length()) { arr.optString(it) }
        } else {
            listOf(values.optString(k))
        }
    }
    return copy(options = options + patch)
}
