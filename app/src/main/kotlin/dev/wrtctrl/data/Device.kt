package dev.wrtctrl.data

import android.util.Log
import org.json.JSONObject

/**
 * 设备条目（
 * 密码仅在内存中为明文，落盘经 SecureStore 加密）。
 */
data class Device(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val useHttps: Boolean,
    val username: String,
    val password: String,
    /** TOFU 叶证书指纹：首连捕获后持久化，后续登录 core 比对 */
    val certSha256: String? = null,
) {
    /** 地址显示：默认端口省略（备注为空时用它做显示名） */
    val displayAddress: String
        get() {
            val defaultPort = if (useHttps) 443 else 80
            return if (port == defaultPort) host else "$host:$port"
        }

    val displayName: String get() = name.ifBlank { displayAddress }

    val baseUrl: String get() = "${if (useHttps) "https" else "http"}://$host:$port"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("host", host)
        put("port", port)
        put("useHttps", useHttps)
        put("username", username)
        put("password", SecureStore.encrypt(password))
        if (!certSha256.isNullOrBlank()) put("certSha256", certSha256)
    }

    companion object {
        /** 解密失败（Keystore 密钥被系统失效/存储损坏）降级为空密码：设备保留，连接时走 auth 错误引导重输 */
        fun fromJson(obj: JSONObject): Device = Device(
            id = obj.getString("id"),
            name = obj.optString("name"),
            host = obj.getString("host"),
            port = obj.optInt("port", 80),
            useHttps = obj.optBoolean("useHttps", false),
            username = obj.getString("username"),
            password = runCatching { SecureStore.decrypt(obj.getString("password")) }
                .getOrElse {
                    Log.w("wrtctrl", "credential decrypt failed, password cleared: ${it.message}")
                    ""
                },
            certSha256 = obj.optString("certSha256").takeIf { it.isNotBlank() },
        )
    }
}
