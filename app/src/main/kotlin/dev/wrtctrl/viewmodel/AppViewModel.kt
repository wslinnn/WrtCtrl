package dev.wrtctrl.viewmodel

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.wrtctrl.R
import dev.wrtctrl.bridge.CoreException
import dev.wrtctrl.bridge.WrtCore
import dev.wrtctrl.data.Device
import dev.wrtctrl.data.DeviceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class Phase { Boot, Gate, Main }

enum class GateMode { List, Form }

data class FormState(
    val host: String = "192.168.1.1",
    val port: String = "80",
    val useHttps: Boolean = false,
    val username: String = "root",
    val password: String = "",
    val name: String = "",
)

data class GateUiState(
    val mode: GateMode = GateMode.Form,
    val devices: List<Device> = emptyList(),
    /** id → 往返毫秒（null=离线）；键不存在=检测中 */
    val pings: Map<String, Long?> = emptyMap(),
    val bannerError: String? = null,
    val form: FormState = FormState(),
    /** 字段名 → 文案资源 id */
    val fieldErrors: Map<String, Int> = emptyMap(),
    val formErrorText: String? = null,
    val formErrorCode: String? = null,
    val connecting: Boolean = false,
    val editingId: String? = null,
)

/**
 * 应用级门控状态机：Boot（探活上次设备）→ Gate（设备门控页）→ Main（五 Tab）。
 * 门控判定在此，门控页只消费状态。
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = DeviceRepository(application)

    private val _phase = MutableStateFlow(Phase.Boot)
    val phase: StateFlow<Phase> = _phase

    private val _current = MutableStateFlow<Device?>(null)
    val current: StateFlow<Device?> = _current

    private val _gate = MutableStateFlow(GateUiState())
    val gate: StateFlow<GateUiState> = _gate

    init {
        viewModelScope.launch { boot() }
    }

    fun str(@StringRes id: Int): String = getApplication<Application>().getString(id)

    private suspend fun boot() {
        val current = repo.current()
        if (current == null) {
            val devices = repo.list()
            _gate.update {
                it.copy(mode = if (devices.isEmpty()) GateMode.Form else GateMode.List, devices = devices)
            }
            _phase.value = Phase.Gate
            pingAll(devices)
            return
        }
        if (activate(current)) {
            _phase.value = Phase.Main
        } else {
            val devices = repo.list()
            _gate.update {
                it.copy(
                    mode = GateMode.List,
                    devices = devices,
                    bannerError = str(R.string.device_list_reconnect_failed),
                )
            }
            _phase.value = Phase.Gate
            pingAll(devices)
        }
    }

    /** 连接并校验会话：探活失败自动用存储凭证重登 */
    private suspend fun activate(device: Device): Boolean = try {
        WrtCore.setDevice(device.baseUrl, device.username, device.password, null)
        WrtCore.reconnect()
        _current.value = device
        true
    } catch (e: CoreException) {
        false
    }

    /** 并行探活全部设备（ping_url 与当前会话无关，可任意目标） */
    private fun pingAll(devices: List<Device>) {
        devices.forEach { device ->
            viewModelScope.launch {
                val ms = runCatching { WrtCore.pingUrl(device.baseUrl) }.getOrNull()
                _gate.update { it.copy(pings = it.pings + (device.id to ms)) }
            }
        }
    }

    /** 列表点卡片直连；失败留在列表并给横幅 */
    fun connectTo(device: Device) {
        viewModelScope.launch {
            _gate.update { it.copy(connecting = true, bannerError = null) }
            val ok = activate(device)
            _gate.update {
                it.copy(
                    connecting = false,
                    bannerError = if (ok) null else str(R.string.device_list_reconnect_failed),
                )
            }
            if (ok) _phase.value = Phase.Main
        }
    }

    /** 顶栏设备入口：回门控页列表形态（快速切换） */
    fun openDeviceList() {
        viewModelScope.launch {
            val devices = repo.list()
            _gate.update {
                it.copy(
                    mode = if (devices.isEmpty()) GateMode.Form else GateMode.List,
                    devices = devices,
                    bannerError = null,
                )
            }
            pingAll(devices)
            _phase.value = Phase.Gate
        }
    }

    fun openForm(device: Device?) {
        _gate.update {
            it.copy(
                mode = GateMode.Form,
                editingId = device?.id,
                bannerError = null,
                fieldErrors = emptyMap(),
                formErrorText = null,
                formErrorCode = null,
                form = if (device != null) {
                    FormState(
                        host = device.host,
                        port = device.port.toString(),
                        useHttps = device.useHttps,
                        username = device.username,
                        password = device.password,
                        name = device.name,
                    )
                } else {
                    FormState()
                },
            )
        }
    }

    /** 表单返回列表；无设备时表单不可退（首跑场景） */
    fun backToList() = openDeviceList()

    fun updateForm(transform: (FormState) -> FormState) {
        _gate.update { it.copy(form = transform(it.form)) }
    }

    /** 协议切换：80↔443 智能跟随 */
    fun setUseHttps(useHttps: Boolean) {
        _gate.update { state ->
            val port = state.form.port
            val newPort = when {
                useHttps && port == "80" -> "443"
                !useHttps && port == "443" -> "80"
                else -> port
            }
            state.copy(form = state.form.copy(useHttps = useHttps, port = newPort))
        }
    }

    fun deleteDevice(id: String) {
        viewModelScope.launch {
            repo.delete(id)
            val devices = repo.list()
            _gate.update {
                it.copy(
                    devices = devices,
                    mode = if (devices.isEmpty()) GateMode.Form else it.mode,
                )
            }
        }
    }

    /** 表单提交：字段校验 → 登录（密码仅限长度；备注可选）→ 落库进主界面 */
    fun submit() {
        val form = _gate.value.form
        val errors = mutableMapOf<String, Int>()
        if (form.host.isBlank()) {
            errors["host"] = R.string.device_list_host_required
        } else if (!isHostValid(form.host)) {
            errors["host"] = R.string.device_list_host_format_error
        }
        val port = form.port.toIntOrNull()
        if (form.port.isBlank()) {
            errors["port"] = R.string.device_list_port_required
        } else if (port == null || port !in 1..65535) {
            errors["port"] = R.string.device_list_port_range_error
        }
        if (form.username.isBlank()) {
            errors["username"] = R.string.device_list_username_required
        } else if (!USERNAME_CHARS.matches(form.username)) {
            errors["username"] = R.string.device_list_username_format_error
        }
        // B7：密码只限长度，空格/任意符号合法（旧字符白名单是会拒绝合法密码的 bug）
        if (form.password.length > 64) {
            errors["password"] = R.string.device_list_password_length_error
        }
        if (form.name.length > 64) {
            errors["name"] = R.string.device_list_remark_length_error
        }
        if (errors.isNotEmpty()) {
            _gate.update { it.copy(fieldErrors = errors) }
            return
        }
        val checkedPort = port ?: 80
        _gate.update {
            it.copy(fieldErrors = emptyMap(), connecting = true, formErrorText = null, formErrorCode = null)
        }
        viewModelScope.launch {
            try {
                val device = Device(
                    id = _gate.value.editingId ?: UUID.randomUUID().toString(),
                    name = form.name,
                    host = form.host,
                    port = checkedPort,
                    useHttps = form.useHttps,
                    username = form.username,
                    password = form.password,
                )
                WrtCore.setDevice(device.baseUrl, device.username, device.password, null)
                WrtCore.login()
                if (_gate.value.editingId != null && repo.get(device.id) != null) {
                    repo.update(device)
                } else {
                    repo.add(
                        host = device.host, port = device.port, useHttps = device.useHttps,
                        username = device.username, password = device.password, name = device.name,
                    ).let { saved -> repo.setCurrent(saved.id) }
                }
                if (_gate.value.editingId != null) repo.setCurrent(device.id)
                _current.value = device
                _phase.value = Phase.Main
            } catch (e: CoreException) {
                // 原始错误链只进 logcat（tag=wrtctrl），不上 UI——UI 只显示分类后的指引文案
                android.util.Log.w("wrtctrl", "connect failed: code=${e.code} ubus=${e.ubus} msg=${e.message}")
                val textRes = when (e.code) {
                    "auth" -> R.string.device_list_error_auth
                    "certificate", "tls" -> R.string.device_list_error_certificate
                    "dns" -> R.string.device_list_error_dns
                    "refused" -> R.string.device_list_error_refused
                    "network", "timeout" -> R.string.device_list_error_network
                    else -> R.string.device_list_error_other
                }
                _gate.update {
                    it.copy(
                        connecting = false,
                        formErrorText = str(textRes),
                        formErrorCode = e.code,
                    )
                }
            } catch (e: Exception) {
                android.util.Log.w("wrtctrl", "connect failed: ${e.message}")
                _gate.update {
                    it.copy(
                        connecting = false,
                        formErrorText = str(R.string.device_list_error_other),
                        formErrorCode = "other",
                    )
                }
            }
        }
    }

    private companion object {
        val USERNAME_CHARS = Regex("^[a-zA-Z0-9._\\-@]+$")

        /** ASCII 集合 + 无连续/首尾点横线 + IPv4 段 ≤255 */
        fun isHostValid(raw: String): Boolean {
            val s = raw.trim()
            if (s.isEmpty() || s.length > 64) return false
            if (!s.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._:%-" }) return false
            if (!s.any { it.isDigit() || it in 'a'..'z' || it in 'A'..'Z' }) return false
            if (s.contains("..") || s.contains("--")) return false
            if (s.first() == '.' || s.first() == '-' || s.last() == '.' || s.last() == '-') return false
            val octets = s.split('.')
            if (octets.size == 4 && octets.all { o -> o.isNotEmpty() && o.all(Char::isDigit) }) {
                return octets.all { it.toIntOrNull() in 0..255 }
            }
            return true
        }
    }
}
