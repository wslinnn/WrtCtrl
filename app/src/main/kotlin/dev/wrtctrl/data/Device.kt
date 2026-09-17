package dev.wrtctrl.data

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
) {
    /** 地址显示：默认端口省略（备注为空时用它做显示名） */
    val displayAddress: String
        get() = if ((!useHttps && port == 80) || (useHttps && port == 443)) host else "$host:$port"

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
    }

    companion object {
        fun fromJson(obj: JSONObject): Device = Device(
            id = obj.getString("id"),
            name = obj.optString("name"),
            host = obj.getString("host"),
            port = obj.optInt("port", 80),
            useHttps = obj.optBoolean("useHttps", false),
            username = obj.getString("username"),
            password = SecureStore.decrypt(obj.getString("password")),
        )
    }
}
