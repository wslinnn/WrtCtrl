package dev.wrtctrl.viewmodel.plugin

import android.app.Application
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

/**
 * 简单插件 VM：列表/写序列全部由 UciPluginViewModel 基类承担，
 * 子类只声明 config/initScript 与候选类别；多类型分流（samba）派生自全量 entries。
 */

/** IP/MAC 绑定：arpbind 配置 + arpbind init（注意 enabled 反逻辑在 schema 层表达） */
class ArpbindViewModel(application: Application) : UciPluginViewModel(application, "arpbind", "arpbind") {
    override fun candidateKinds() = listOf(
        UciCandidates.HOSTHINTS_IP,
        UciCandidates.HOSTHINTS_MAC,
        UciCandidates.DEVICES,
    )
}

/** 定时重启：无候选 */
class AutorebootViewModel(application: Application) : UciPluginViewModel(application, "autoreboot", "autoreboot")

/** CIFS 挂载：无候选（local_path 静态选项） */
class CifsViewModel(application: Application) : UciPluginViewModel(application, "cifs-mount", "cifs-mount")

/**
 * 网络共享：samba 全局段（仅编辑）+ sambashare 列表同存一个 config——sectionType 保持 null，
 * 屏层按 type 分流（shares = filter sambashare；global = first samba）；
 * 另有 Edit Template（/etc/samba/smb.conf.template 文本编辑，file 通道）。
 */
class SambaViewModel(application: Application) : UciPluginViewModel(application, "samba4", "samba4") {
    override fun candidateKinds() = listOf(UciCandidates.INTERFACES)

    /** 模板编辑运行态（独立于 uci 写路径；与 LuCI 一致（Edit Template tab） */
    data class TemplateUi(
        val loading: Boolean = false,
        val text: String = "",
        val loadFailed: Boolean = false,
        val saving: Boolean = false,
    )

    private val _template = MutableStateFlow(TemplateUi())
    val template: StateFlow<TemplateUi> = _template

    fun loadTemplate() {
        if (_template.value.loading) return
        viewModelScope.launch {
            _template.update { it.copy(loading = true) }
            try {
                val text = withContext(Dispatchers.IO) { WrtCore.readFile(TEMPLATE_PATH) }
                _template.value = TemplateUi(loading = false, text = text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("wrtctrl", "smb.conf.template read failed: ${e.message}")
                _template.value = TemplateUi(loading = false, loadFailed = true)
            }
        }
    }

    /**
     * 模板保存（与 LuCI 一致）：规范化 trim + CRLF→LF + 末尾补 \n → writeFile → apply(samba4,
     * restart)——模板变更需 smbd 全量重读（原版 ucitrack init samba 即 restart）。
     */
    fun saveTemplate(text: String, onResult: (Boolean) -> Unit) {
        if (_template.value.saving) {
            onResult(false)
            return
        }
        val normalized = text.trim().replace("\r\n", "\n") + "\n"
        viewModelScope.launch {
            _template.update { it.copy(saving = true) }
            var ok = try {
                withContext(Dispatchers.IO) { WrtCore.writeFile(TEMPLATE_PATH, normalized) }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("wrtctrl", "smb.conf.template write failed: ${e.message}")
                false
            }
            if (ok) {
                try {
                    withContext(Dispatchers.IO) { WrtCore.apply("samba4", "restart") }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("wrtctrl", "samba4 restart failed: ${e.message}")
                }
            }
            _template.update {
                if (ok) it.copy(saving = false, text = normalized) else it.copy(saving = false)
            }
            onResult(ok)
        }
    }

    private companion object {
        const val TEMPLATE_PATH = "/etc/samba/smb.conf.template"
    }
}

/** USB 打印机：detectlp 发现明细 + printers 候选（保存成功后需重探） */
class UsbPrinterViewModel(application: Application) : UciPluginViewModel(application, "usb_printer", "usb_printer") {
    override fun candidateKinds() = listOf(UciCandidates.PRINTERS, UciCandidates.IFADDRS)

    data class PrintersUi(
        val loading: Boolean = false,
        val details: List<PrinterDetail> = emptyList(),
        val failed: Boolean = false,
    )

    private val _printers = MutableStateFlow(PrintersUi())
    val printers: StateFlow<PrintersUi> = _printers

    /** 一次性扫描失败事件（探测命令失败时 toast 加载失败；Screen 提示后 consume） */
    private val _scanError = MutableStateFlow(false)
    val scanError: StateFlow<Boolean> = _scanError

    fun consumeScanError() {
        _scanError.value = false
    }

    /** detectlp 重探：更新发现列表 + 覆盖 printers 候选（绑定编辑页设备下拉） */
    fun loadPrinters() {
        viewModelScope.launch {
            _printers.update { it.copy(loading = true) }
            try {
                val data = withContext(Dispatchers.IO) { WrtCore.usbPrinters() }
                val details = UciParsers.printerDetails(data)
                val options = UciParsers.candidates(data.optJSONArray("printers"))
                _printers.value = PrintersUi(loading = false, details = details)
                candidateCache.update { it + (UciCandidates.PRINTERS.kind to options) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("wrtctrl", "detectlp failed: ${e.message}")
                _printers.value = PrintersUi(loading = false, failed = true)
                _scanError.value = true
            }
        }
    }
}

/** 超级网络唤醒：hosthints-mac + devices 候选 + 行级唤醒（luci.wolultra wake） */
class WolViewModel(application: Application) : UciPluginViewModel(application, "wolultra", "wolultra") {
    override fun candidateKinds() = listOf(
        UciCandidates.HOSTHINTS_MAC,
        UciCandidates.DEVICES,
    )

    /** 唤醒运行态（独立于基类写状态——唤醒不走 uci） */
    data class WakeUi(
        /** 唤醒进行中的 section（行级按钮 loading + 禁点） */
        val wakingSection: String? = null,
        /** 成功 toast：目标显示名（消费后清空） */
        val sentName: String? = null,
        /** 失败弹窗：目标显示名 to 输出尾串（消费后清空） */
        val failed: Pair<String, String>? = null,
    )

    private val _wake = MutableStateFlow(WakeUi())
    val wake: StateFlow<WakeUi> = _wake

    fun consumeSent() {
        _wake.update { it.copy(sentName = null) }
    }

    fun consumeFailed() {
        _wake.update { it.copy(failed = null) }
    }

    fun wake(entry: UciEntry, displayName: String) {
        val mac = entry.first("macaddr").orEmpty()
        if (!WOL_MAC_PATTERN.matches(mac)) {
            _wake.update {
                it.copy(failed = displayName to getApplication<Application>().getString(R.string.wolultra_mac_invalid))
            }
            return
        }
        if (_wake.value.wakingSection != null) return
        viewModelScope.launch {
            _wake.update { it.copy(wakingSection = entry.section) }
            try {
                val data = withContext(Dispatchers.IO) {
                    WrtCore.callUbus(
                        "luci.wolultra",
                        "wake",
                        JSONObject().put("iface", entry.first("maceth") ?: "br-lan").put("mac", mac),
                    )
                }
                // 判定：code===0 或缺省（null）= 成功；失败带 stdout/stderr 尾串弹窗
                val code = data.opt("code")
                val ok = code == null || (code as? Number)?.toInt() == 0
                if (ok) {
                    _wake.update { it.copy(wakingSection = null, sentName = displayName) }
                } else {
                    val out = (data.optString("stdout") + data.optString("stderr")).trim()
                        .let { if (it.length > OUTPUT_TAIL) it.takeLast(OUTPUT_TAIL) else it }
                    _wake.update { it.copy(wakingSection = null, failed = displayName to out) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("wrtctrl", "wol wake failed: ${e.message}")
                _wake.update {
                    it.copy(
                        wakingSection = null,
                        failed = displayName to getApplication<Application>().getString(R.string.wolultra_wake_failed),
                    )
                }
            }
        }
    }

    private companion object {
        val WOL_MAC_PATTERN = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
        const val OUTPUT_TAIL = 300
    }
}
