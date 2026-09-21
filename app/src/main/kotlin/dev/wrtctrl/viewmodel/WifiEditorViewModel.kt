package dev.wrtctrl.viewmodel

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.wrtctrl.R
import dev.wrtctrl.bridge.WrtCore
import dev.wrtctrl.viewmodel.plugin.UciCandidates
import dev.wrtctrl.viewmodel.plugin.UciEntry
import dev.wrtctrl.viewmodel.plugin.UciOption
import dev.wrtctrl.viewmodel.plugin.UciParsers
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

/** wifi 编辑页状态 */
data class WifiEditorUiState(
    val loading: Boolean = true,
    /** 目标 section（wifi-device / wifi-iface 原样）；加载失败或目标不存在 = null */
    val entry: UciEntry? = null,
    val isRadio: Boolean = false,
    /** MTK 分支：radio 自身 / iface 所属 radio 的 type ∈ MTK 三分支检测集 */
    val mtk: Boolean = false,
    /** radio band='2g'（noscan 仅 2.4G 渲染；标准固件常无 band 选项 → 隐藏，无碍） */
    val band2g: Boolean = false,
    val saving: Boolean = false,
    @StringRes val writeErrorRes: Int? = null,
)

/**
 * wifi 编辑器 VM：uciGet("wireless") 取目标段（表单事实源）+ radio 编辑并行拉 iwinfo
 * 四类候选（freqlist/htmodes/txpowerlist/countrylist，device=radio section 名；失败回退
 * 静态枚举，候选缺失不阻塞编辑）。保存 = uciSet → uciCommit("wireless")（session 预检
 * + apply{rollback:120} + confirm + commit_lock；ucitrack 自动重配无线，无 init.d apply）。
 * 断连窗口：保存作用于当前所连 radio 时手机会短暂掉网——apply 与 confirm 在 core 同一
 * 写序列内连发，与 LuCI/浏览器同语义；掉网期间轮询失败静默保留旧值（既有纪律）。
 */
class WifiEditorViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(WifiEditorUiState())
    val state: StateFlow<WifiEditorUiState> = _state

    /** kind → 候选表（radio 编辑专用；iface 编辑全静态选项，此表为空） */
    private val candidateCache = MutableStateFlow<Map<String, List<UciOption>>>(emptyMap())
    val candidates: StateFlow<Map<String, List<UciOption>>> = candidateCache

    /** 保存成功一次性事件（toast 后 consume） */
    private val _doneEvent = MutableStateFlow<Int?>(null)
    val doneEvent: StateFlow<Int?> = _doneEvent

    fun consumeDoneEvent() {
        _doneEvent.value = null
    }

    fun consumeWriteError() {
        _state.update { it.copy(writeErrorRes = null) }
    }

    private var loadedKey: String? = null
    private var section: String = ""

    fun ensureLoaded(deviceId: String?, target: String) {
        val key = "$deviceId|$target"
        if (key == loadedKey) return
        loadedKey = key
        section = target
        _state.value = WifiEditorUiState()
        candidateCache.value = emptyMap()
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val data = try {
                withContext(Dispatchers.IO) { WrtCore.uciGet(WIRELESS_CONFIG) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("wrtctrl", "wifi editor load failed: ${e.message}")
                _state.update { it.copy(loading = false) }
                return@launch
            }
            val entries = UciParsers.entries(data)
            val entry = entries.firstOrNull { it.section == section }
            if (entry == null) {
                _state.update { it.copy(loading = false) }
                return@launch
            }
            val isRadio = entry.type == RADIO_TYPE
            val radio = if (isRadio) {
                entry
            } else {
                entries.firstOrNull { it.type == RADIO_TYPE && it.section == entry.first("device") }
            }
            _state.update {
                it.copy(
                    loading = false,
                    entry = entry,
                    isRadio = isRadio,
                    mtk = radio?.first("type").orEmpty() in MTK_TYPES,
                    band2g = entry.first("band") == "2g",
                )
            }
            if (isRadio) loadCandidates(section)
        }
    }

    /** iwinfo 四类并行拉取；失败降级空表（fieldOptions 回退静态枚举），logcat 留痕 */
    private suspend fun loadCandidates(radioName: String) {
        val gen = loadedKey
        val map = HashMap<String, List<UciOption>>()
        coroutineScope {
            listOf(
                UciCandidates.FREQLIST,
                UciCandidates.HTMODES,
                UciCandidates.TXPOWERLIST,
                UciCandidates.COUNTRYLIST,
            ).map { kind ->
                async(Dispatchers.IO) {
                    try {
                        kind.kind to UciParsers.candidates(
                            withContext(Dispatchers.IO) { WrtCore.candidates(kind.kind, radioName) },
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w("wrtctrl", "iwinfo candidates ${kind.kind} failed: ${e.message}")
                        null
                    }
                }
            }.forEach { result ->
                result.await()?.let { (kind, options) -> map[kind] = options }
            }
        }
        if (loadedKey == gen) candidateCache.value = map
    }

    /** 保存：set → commit；成功回调由 Screen 关页并触发网络页无线强刷 */
    fun submit(values: JSONObject, onResult: (Boolean) -> Unit) {
        if (_state.value.saving) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            val ok = try {
                withContext(Dispatchers.IO) {
                    WrtCore.uciSet(WIRELESS_CONFIG, section, values)
                    WrtCore.uciCommit(WIRELESS_CONFIG)
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("wrtctrl", "wifi editor save failed: ${e.message}")
                false
            }
            _state.update {
                it.copy(saving = false, writeErrorRes = if (ok) null else R.string.plugin_save_failed)
            }
            if (ok) {
                _doneEvent.value = R.string.plugin_save_success
                onResult(true)
            } else {
                onResult(false)
            }
        }
    }

    private companion object {
        const val WIRELESS_CONFIG = "wireless"
        const val RADIO_TYPE = "wifi-device"

        /** MTK 固件三分支检测集（luci-mtk 自身只认 'mtwifi'） */
        val MTK_TYPES = setOf("mtwifi", "mtk", "mtkwifi")
    }
}
