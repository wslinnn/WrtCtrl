package dev.wrtctrl.viewmodel.plugin

import android.app.Application
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
import org.json.JSONObject

/**
 * UPnP VM：config/perm_rule 两类段走基类（sectionType=null 屏层分流）；
 * 活跃映射 = luci.upnp get_status（）5s 轮询——覆盖页仅打开时组合，
 * PollingGate 驱动 visible；delay 醒来复查门控（纪律）；失败静默保旧值。
 */
class UpnpViewModel(application: Application) : UciPluginViewModel(application, "upnpd", "miniupnpd") {

    /** 活跃映射运行态 */
    data class ActiveUi(
        val rules: List<UpnpRuleUi> = emptyList(),
        /** 删除进行中的 token（行级禁点） */
        val deleting: String? = null,
    )

    private val _active = MutableStateFlow(ActiveUi())
    val active: StateFlow<ActiveUi> = _active

    /** 一次性删除结果提示（true=成功；Screen 提示后 consume） */
    private val _deleteEvent = MutableStateFlow<Boolean?>(null)
    val deleteEvent: StateFlow<Boolean?> = _deleteEvent

    private val visible = MutableStateFlow(false)
    private var rulesBusy = false

    fun setVisible(v: Boolean) {
        visible.value = v
    }

    fun consumeDeleteEvent() {
        _deleteEvent.value = null
    }

    init {
        viewModelScope.launch {
            while (viewModelScope.isActive) {
                visible.first { it }
                delay(POLL_INTERVAL)
                // delay 期间覆盖页可能已退出：拉取前复查，避免关停后多刷一次
                if (visible.value && !rulesBusy) loadRules()
            }
        }
    }

    /** 进页首拉（与轮询共用 busy 互斥）；失败静默保旧值（轮询类纪律） */
    fun loadRules() {
        if (rulesBusy) return
        viewModelScope.launch {
            rulesBusy = true
            val gen = generation
            try {
                val data = withContext(Dispatchers.IO) {
                    WrtCore.callUbus("luci.upnp", "get_status")
                }
                val rules = UciParsers.upnpRules(data)
                if (gen != generation) return@launch
                _active.update { it.copy(rules = rules) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("wrtctrl", "upnp get_status failed: ${e.message}")
            } finally {
                rulesBusy = false
            }
        }
    }

    /** 删除活跃映射：luci.upnp delete_rule{token}（非 uci 通道，无需 commit） */
    fun deleteRule(rule: UpnpRuleUi) {
        if (_active.value.deleting != null) return
        viewModelScope.launch {
            _active.update { it.copy(deleting = rule.num) }
            val gen = generation
            var ok = try {
                withContext(Dispatchers.IO) {
                    WrtCore.callUbus("luci.upnp", "delete_rule", JSONObject().put("token", rule.num))
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("wrtctrl", "upnp delete_rule failed: ${e.message}")
                false
            }
            if (ok) {
                // 删除后立即重拉（成功与否以回读为准）；重拉失败不影响删除已生效
                try {
                    val data = withContext(Dispatchers.IO) { WrtCore.callUbus("luci.upnp", "get_status") }
                    val rules = UciParsers.upnpRules(data)
                    if (gen == generation) _active.update { it.copy(rules = rules) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("wrtctrl", "upnp get_status after delete failed: ${e.message}")
                }
            }
            if (gen != generation) return@launch
            _active.update { it.copy(deleting = null) }
            _deleteEvent.value = ok
        }
    }

    private companion object {
        const val POLL_INTERVAL = 5000L
    }
}
