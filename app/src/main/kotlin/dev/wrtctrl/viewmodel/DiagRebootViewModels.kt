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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

// ── 诊断（ping/traceroute/nslookup）──

data class DiagUiState(
    val running: Boolean = false,
    /** null=无结果（空态）；空串=命令无输出（仍显示结果卡） */
    val output: String? = null,
    val errorRes: Int? = null,
)

class DiagViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(DiagUiState())
    val state: StateFlow<DiagUiState> = _state

    /** 代次守卫：clear()/新 run() 各 +1，在飞响应按代次丢弃——切换分段或重发请求后，
     *  旧工具/旧参数的结果不得回填（clear 不取消协程，靠代次使其结果失效） */
    private var generation = 0

    /** kind ∈ ping/traceroute/nslookup；count/hops≤0 时 core 走默认值 */
    fun run(kind: String, host: String, count: Int, hops: Int) {
        val target = host.trim()
        if (target.isEmpty() || _state.value.running) return
        val gen = ++generation
        viewModelScope.launch {
            _state.update { DiagUiState(running = true) }
            try {
                val result = withContext(Dispatchers.IO) {
                    when (kind) {
                        "ping" -> WrtCore.diagPing(target, count, wait = 2, deadline = 8)
                        "traceroute" -> WrtCore.diagTraceroute(target, hops, wait = 1, queries = 1)
                        else -> WrtCore.diagNslookup(target)
                    }
                }
                if (gen != generation) return@launch
                // 诊断命令常把错误写 stderr（如 Name or service not known），一并展示
                val text = (result.stdout + (result.stderr.takeIf(String::isNotBlank)?.let { "\n$it" } ?: ""))
                    .trimEnd('\n')
                _state.update { it.copy(running = false, output = text) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation) return@launch
                android.util.Log.w("wrtctrl", "diag $kind failed: ${e.message}")
                _state.update { it.copy(running = false, errorRes = R.string.diag_load_failed) }
            }
        }
    }

    fun clear() {
        generation++
        _state.value = DiagUiState()
    }
}

// ── 重启 ──

data class RebootUiState(
    /** 已下发/正在下发重启指令（设备随后断连属预期，错误吞掉） */
    val rebooting: Boolean = false,
    /** 倒计时截止时刻（SystemClock.elapsedRealtime 基准）：存 VM 而非组合——
     *  旋转/切主题重建、离开再返回都据此续算而非重置 */
    val deadlineElapsed: Long? = null,
)

class RebootViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(RebootUiState())
    val state: StateFlow<RebootUiState> = _state

    /** 倒计时归零的一次性事件：计时在 VM（不挂组合），离开页面/重建都不中断——
     *  60s 到点必须回设备列表（旧会话已随重启失效） */
    private val _rebootCountdownDone = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val rebootCountdownDone: SharedFlow<Unit> = _rebootCountdownDone

    fun reboot() {
        if (_state.value.rebooting) return
        // 下发即置位：15s 超时窗口内防重复触发，UI 立即进入倒计时反馈
        _state.update {
            it.copy(
                rebooting = true,
                deadlineElapsed = SystemClock.elapsedRealtime() + REBOOT_COUNTDOWN_MS,
            )
        }
        // 倒计时独立计时：不依赖下发调用何时返回（指令断连/超时都属预期）
        viewModelScope.launch {
            delay(REBOOT_COUNTDOWN_MS)
            _rebootCountdownDone.tryEmit(Unit)
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    WrtCore.callUbus("system", "reboot", JSONObject(), 15000)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 重启指令发出后连接中断/超时属预期，吞掉（旧 .catch(()=>{}) 语义）
                android.util.Log.w("wrtctrl", "reboot command: ${e.message}")
            }
        }
    }

    private companion object {
        const val REBOOT_COUNTDOWN_MS = 60_000L
    }
}
