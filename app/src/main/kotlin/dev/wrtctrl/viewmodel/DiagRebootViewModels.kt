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

    /** kind ∈ ping/traceroute/nslookup；count/hops≤0 时 core 走默认值 */
    fun run(kind: String, host: String, count: Int, hops: Int) {
        val target = host.trim()
        if (target.isEmpty() || _state.value.running) return
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
                // 诊断命令常把错误写 stderr（如 Name or service not known），一并展示
                val text = (result.stdout + (result.stderr.takeIf(String::isNotBlank)?.let { "\n$it" } ?: ""))
                    .trimEnd('\n')
                _state.update { it.copy(running = false, output = text) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("wrtctrl", "diag $kind failed: ${e.message}")
                _state.update { it.copy(running = false, errorRes = R.string.diag_load_failed) }
            }
        }
    }

    fun clear() {
        _state.value = DiagUiState()
    }
}

// ── 重启 ──

data class RebootUiState(
    /** 已下发重启指令（设备随后断连属预期，错误吞掉） */
    val rebooting: Boolean = false,
)

class RebootViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(RebootUiState())
    val state: StateFlow<RebootUiState> = _state

    fun reboot() {
        if (_state.value.rebooting) return
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
            _state.update { it.copy(rebooting = true) }
        }
    }
}
