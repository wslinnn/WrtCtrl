package dev.wrtctrl.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rust core 错误（code 对齐 UbusError 分类；ubus 原始错误码随行，UI 层对
 * 6=权限/11、12=超时类有分支语义）。
 */
class CoreException(
    val code: String,
    message: String,
    val ubus: Int = -1,
) : Exception(message)

/**
 * Rust core 的 JNI 入口封装。
 *
 * 桥接模式：导出为阻塞式（Rust 内部 block_on），Kotlin 统一在
 * Dispatchers.IO 调用——当前全部为对答式调用，无推送场景，不引入 reqId 回调机制。
 * 统一应答信封：{"ok":true,"data":…} / {"ok":false,"error":{code,message,ubus?}}。
 */
// TooManyFunctions：本对象是原生导出的一比一门面（每个 JNI 导出一个 suspend 包装），
// 函数数由 core 能力面决定，拆分只会制造间接层
@Suppress("TooManyFunctions")
object WrtCore {
    init {
        System.loadLibrary("wrtctrl_jni")
    }

    fun hello(): String = helloNative()

    /** 返回 "caught panic: ..." = 防线生效；进程直接死掉 = 防线失效（必须修） */
    fun panicTest(): String = panicTestNative()

    // ── 原生导出（阻塞式）──

    private external fun helloNative(): String
    private external fun panicTestNative(): String
    private external fun setDeviceNative(cfgJson: String): String
    private external fun loginNative(): String
    private external fun reconnectNative(): String
    private external fun callUbusNative(objectName: String, method: String, paramsJson: String, timeoutMs: Int): String
    private external fun uciGetNative(config: String): String
    private external fun uciAddNative(config: String, sectionType: String): String
    private external fun uciSetNative(config: String, section: String, valuesJson: String): String
    private external fun uciDeleteNative(config: String, section: String): String
    private external fun uciOrderNative(config: String, sectionsJson: String): String
    private external fun uciCommitNative(config: String): String
    private external fun applyNative(initScript: String, action: String): String
    private external fun candidatesNative(kind: String): String
    private external fun readFileNative(path: String): String
    private external fun writeFileNative(path: String, data: String, mode: String): String
    private external fun readSyslogNative(): String
    private external fun readDmesgNative(): String
    private external fun wirelessStatusNative(): String
    private external fun assocListNative(ifname: String): String
    private external fun setRadioEnabledNative(radioName: String, enabled: Boolean): String
    private external fun restartRadioNative(radioName: String): String
    private external fun pingUrlNative(baseUrl: String): String
    private external fun diagPingNative(host: String, count: Int, wait: Int, deadline: Int): String
    private external fun diagTracerouteNative(host: String, maxHops: Int, wait: Int, queries: Int): String
    private external fun diagNslookupNative(host: String, dnsServer: String): String

    // ── 信封解包 ──

    private suspend fun envelope(native: () -> String): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(native())
    }

    /** 执行并解包信封；失败抛 CoreException。返回整个信封（data 形状各异时用） */
    private suspend fun call(native: () -> String): JSONObject {
        val response = envelope(native)
        if (!response.optBoolean("ok", false)) {
            val error = response.optJSONObject("error") ?: JSONObject()
            throw CoreException(
                error.optString("code", "invalid_response"),
                error.optString("message", "core call failed"),
                error.optInt("ubus", -1),
            )
        }
        return response
    }

    private suspend fun dataObject(native: () -> String): JSONObject =
        call(native).getJSONObject("data")

    private suspend fun dataArray(native: () -> String): JSONArray =
        call(native).getJSONArray("data")

    private suspend fun dataString(native: () -> String): String =
        call(native).getString("data")

    // ── 会话与设备 ──

    /** 设置/切换当前设备上下文（baseUrl 形如 http://192.168.1.1:80） */
    suspend fun setDevice(baseUrl: String, username: String, password: String, session: String? = null) {
        val cfg = JSONObject().apply {
            put("baseUrl", baseUrl)
            put("username", username)
            put("password", password)
            if (session != null) put("session", session)
        }
        call { setDeviceNative(cfg.toString()) }
    }

    /** 登录成功返回 ubus_rpc_session（core 内部已就地切换会话） */
    suspend fun login(): String =
        call { loginNative() }.getJSONObject("data").getString("session")

    /** 重连：探活当前会话，失败自动用存储凭证重登 */
    suspend fun reconnect(): String =
        call { reconnectNative() }.getJSONObject("data").getString("session")

    /** 通用 ubus 调用：页面级专用接口之外的兜底通道（页面级专用接口之外的兜底通道） */
    suspend fun callUbus(
        objectName: String,
        method: String,
        params: JSONObject = JSONObject(),
        timeoutMs: Int = 8000,
    ): JSONObject = call { callUbusNative(objectName, method, params.toString(), timeoutMs) }.getJSONObject("data")

    // ── UCI（写路径统一走 rollback 兜底，见 core）──

    suspend fun uciGet(config: String): JSONObject = dataObject { uciGetNative(config) }

    suspend fun uciAdd(config: String, sectionType: String): String =
        dataObject { uciAddNative(config, sectionType) }.getString("section")

    suspend fun uciSet(config: String, section: String, values: JSONObject) {
        call { uciSetNative(config, section, values.toString()) }
    }

    suspend fun uciDelete(config: String, section: String) {
        call { uciDeleteNative(config, section) }
    }

    suspend fun uciOrder(config: String, sections: List<String>) {
        call { uciOrderNative(config, JSONArray(sections).toString()) }
    }

    suspend fun uciCommit(config: String) {
        call { uciCommitNative(config) }
    }

    suspend fun apply(initScript: String, action: String = "reload") {
        call { applyNative(initScript, action) }
    }

    // ── 候选 ──

    suspend fun candidates(kind: String): JSONArray = dataArray { candidatesNative(kind) }
    suspend fun hostHints(): JSONObject = dataObject { candidatesNative("hosthints") }
    suspend fun usbPrinters(): JSONObject = dataObject { candidatesNative("printers") }

    // ── 文件 / 日志 ──

    suspend fun readFile(path: String): String = dataString { readFileNative(path) }
    suspend fun writeFile(path: String, data: String, mode: Int? = null) {
        call { writeFileNative(path, data, mode?.toString() ?: "") }
    }

    suspend fun readSyslog(): JSONArray = dataArray { readSyslogNative() }
    suspend fun readDmesg(): JSONArray = dataArray { readDmesgNative() }

    // ── 无线 ──

    suspend fun wirelessStatus(): JSONObject = dataObject { wirelessStatusNative() }
    suspend fun assocList(ifname: String): JSONArray = dataArray { assocListNative(ifname) }

    suspend fun setRadioEnabled(radioName: String, enabled: Boolean) {
        call { setRadioEnabledNative(radioName, enabled) }
    }

    suspend fun restartRadio(radioName: String) {
        call { restartRadioNative(radioName) }
    }

    // ── 探活 ──

    /** 任意设备（设备列表并行探活用；当前设备探活同样经此以 baseUrl 发起） */
    suspend fun pingUrl(baseUrl: String): Long? =
        call { pingUrlNative(baseUrl) }.getJSONObject("data").optLong("ms").takeIf { it > 0L }

    // ── 诊断：净化/路径/超时全部在 core diag，UI 只传参 ──

    /** 诊断结果：code 可空（命令被信号终止等），stdout/stderr 常合并展示 */
    data class DiagResult(val code: Int?, val stdout: String, val stderr: String)

    /** ping：count/wait/deadline 传 0 走 core 默认（4/2s/8s） */
    suspend fun diagPing(host: String, count: Int, wait: Int, deadline: Int): DiagResult {
        val d = dataObject { diagPingNative(host, count, wait, deadline) }
        return DiagResult(d.optIntOrNull("code"), d.optString("stdout"), d.optString("stderr"))
    }

    /** traceroute：maxHops/wait/queries 传 0 走 core 默认（15/1s/1）；IPv6 目标自动换 traceroute6 */
    suspend fun diagTraceroute(host: String, maxHops: Int, wait: Int, queries: Int): DiagResult {
        val d = dataObject { diagTracerouteNative(host, maxHops, wait, queries) }
        return DiagResult(d.optIntOrNull("code"), d.optString("stdout"), d.optString("stderr"))
    }

    /** nslookup：dnsServer 空串 = 不指定；busybox 无 deadline，core 内部 25s 兜底 */
    suspend fun diagNslookup(host: String, dnsServer: String = ""): DiagResult {
        val d = dataObject { diagNslookupNative(host, dnsServer) }
        return DiagResult(d.optIntOrNull("code"), d.optString("stdout"), d.optString("stderr"))
    }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) getInt(key) else null
}
