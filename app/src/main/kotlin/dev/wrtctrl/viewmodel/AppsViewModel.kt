package dev.wrtctrl.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.wrtctrl.bridge.WrtCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppsUiState(
    /** 首次探测进行中（居中 loading；后续刷新静默） */
    val loading: Boolean = true,
    /** 下拉刷新指示（总时长 = max(探测, 400ms)） */
    val refreshing: Boolean = false,
    /** config 名 → 是否已安装；探测全部失败时为空 map（仅剩固定工具） */
    val installed: Map<String, Boolean> = emptyMap(),
)

/**
 * 应用中心状态：并行 uci get 探测插件安装态（任一路失败互不影响：
 * 任一路成功与否互不影响，整体失败 = 空 map）。页面无轮询——安装态变化频率极低，
 * 进页/下拉刷新/切设备时探测一次即可；generation 守卫丢弃在飞的旧设备结果。
 */
class AppsViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(AppsUiState())
    val state: StateFlow<AppsUiState> = _state

    private var loadedDeviceId: String? = null
    private var generation = 0

    /** deviceId=null 时初始 loadedDeviceId 与之相等直接跳过、loading 停留 true：
     *  当前状态机 Main 相必有 current 设备故不可达；复用本页到他处前需先保证非空 */
    fun ensureLoaded(deviceId: String?) {
        if (deviceId != loadedDeviceId) {
            loadedDeviceId = deviceId
            generation++
            _state.update { AppsUiState() }
            probe()
        }
    }

    fun refresh() {
        // 首载探测进行中不叠加刷新：probe 无互斥，双跑 = 8 路 uciGet 翻倍
        if (_state.value.loading || _state.value.refreshing) return
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            val startedAt = android.os.SystemClock.elapsedRealtime()
            probe()
            holdRefreshSpin(startedAt)
            _state.update { it.copy(refreshing = false) }
        }
    }

    /** 8 路（去重后）并行探测：单路失败=false，整体异常=空 map */
    private fun probe() {
        viewModelScope.launch {
            val gen = generation
            val installed = try {
                coroutineScope {
                    AppRegistry.probeConfigs.map { config ->
                        async { config to probeConfig(config) }
                    }.map { it.await() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("wrtctrl", "plugin probe failed: ${e.message}")
                if (gen == generation) _state.update { it.copy(loading = false, installed = emptyMap()) }
                return@launch
            }
            if (gen != generation) return@launch
            _state.update { it.copy(loading = false, installed = installed.toMap()) }
        }
    }

    /** 单 config 探测：uci get 成功且有载荷 = 已安装；任何失败 = 未安装 */
    private suspend fun probeConfig(config: String): Boolean = try {
        withContext(Dispatchers.IO) { WrtCore.uciGet(config) }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }
}
