package dev.wrtctrl.viewmodel.plugin

import android.app.Application
import androidx.lifecycle.viewModelScope
import dev.wrtctrl.bridge.WrtCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 防火墙 VM：六类共用一次 uciGet("firewall")（sectionType=null，
 * 屏层按 entry.type 分桶 defaults/zone/forwarding/redirect/rule/nat，各桶保持 section 名序）；
 * 常规写序列走基类（config=firewall，编辑器按各自 sectionType 调 submit）。
 */
class FirewallViewModel(application: Application) : UciPluginViewModel(application, "firewall", "firewall") {
    override fun candidateKinds() = listOf(
        UciCandidates.ZONES,
        UciCandidates.INTERFACES,
        // 全量对齐：rule/redirect/nat 的 helper 引用与「使用 ipset」下拉
        UciCandidates.HELPERS,
        UciCandidates.IPSETS,
    )

    /** 自定义规则 /etc/firewall.user（file 通道，独立于 uci 写路径） */
    data class CustomRulesUi(
        val loading: Boolean = false,
        val text: String = "",
        val loadFailed: Boolean = false,
        val saving: Boolean = false,
        /** 保存成功事件计数（Screen 提示后忽略即可，自增型不积压） */
        val savedCount: Int = 0,
    )

    private val _custom = MutableStateFlow(CustomRulesUi())
    val customRules: StateFlow<CustomRulesUi> = _custom

    fun loadCustom() {
        if (_custom.value.loading) return
        viewModelScope.launch {
            _custom.update { it.copy(loading = true) }
            try {
                val text = withContext(Dispatchers.IO) { WrtCore.readFile(CUSTOM_RULES_PATH) }
                _custom.value = CustomRulesUi(loading = false, text = text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("wrtctrl", "firewall.user read failed: ${e.message}")
                _custom.value = CustomRulesUi(loading = false, loadFailed = true)
            }
        }
    }

    /**
     * 自定义规则保存（与 LuCI saveCustom 一致）：规范化 trim + CRLF→LF + 末尾补 \n →
     * writeFile → apply(firewall, restart)——唯一 restart 场景（全量重建），失败不阻断
     * （规则已落盘，下次 fw restart 生效）。
     */
    fun saveCustom(text: String, onResult: (Boolean) -> Unit) {
        if (_custom.value.saving) {
            onResult(false)
            return
        }
        val normalized = text.trim().replace("\r\n", "\n") + "\n"
        viewModelScope.launch {
            _custom.update { it.copy(saving = true) }
            var ok = try {
                withContext(Dispatchers.IO) { WrtCore.writeFile(CUSTOM_RULES_PATH, normalized) }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("wrtctrl", "firewall.user write failed: ${e.message}")
                false
            }
            if (ok) {
                try {
                    withContext(Dispatchers.IO) { WrtCore.apply("firewall", "restart") }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("wrtctrl", "firewall restart failed: ${e.message}")
                }
            }
            _custom.update {
                if (ok) it.copy(saving = false, text = normalized, savedCount = it.savedCount + 1) else it.copy(saving = false)
            }
            onResult(ok)
        }
    }

    private companion object {
        const val CUSTOM_RULES_PATH = "/etc/firewall.user"
    }
}
