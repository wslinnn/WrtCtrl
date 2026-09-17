package dev.wrtctrl.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "wrtctrl")

/**
 * 设备仓储：
 * - 设备列表 + 当前设备 id 存 DataStore；密码经 SecureStore 加密后随 JSON 落盘
 * - CRUD 各一个
 * - 插件安装探测缓存不在此处（UI 层 PluginCatalog 负责）
 */
class DeviceRepository(private val context: Context) {

    suspend fun list(): List<Device> {
        val json = context.dataStore.data.first()[DEVICES_KEY] ?: return emptyList()
        val array = JSONArray(json)
        return (0 until array.length()).map { Device.fromJson(array.getJSONObject(it)) }
    }

    suspend fun add(
        host: String,
        port: Int,
        useHttps: Boolean,
        username: String,
        password: String,
        name: String = "",
    ): Device {
        val device = Device(
            id = UUID.randomUUID().toString(),
            name = name,
            host = host,
            port = port,
            useHttps = useHttps,
            username = username,
            password = password,
        )
        save(list() + device)
        return device
    }

    suspend fun update(device: Device) {
        save(list().map { if (it.id == device.id) device else it })
    }

    suspend fun delete(id: String) {
        save(list().filterNot { it.id == id })
        if (currentId() == id) setCurrent(null)
    }

    suspend fun get(id: String): Device? = list().find { it.id == id }

    fun currentFlow(): Flow<Device?> = context.dataStore.data.map { prefs ->
        val id = prefs[CURRENT_KEY] ?: return@map null
        val json = prefs[DEVICES_KEY] ?: return@map null
        JSONArray(json)
            .let { array -> (0 until array.length()).map { array.getJSONObject(it) } }
            .find { it.optString("id") == id }
            ?.let { Device.fromJson(it) }
    }

    suspend fun current(): Device? = currentFlow().first()

    suspend fun setCurrent(id: String?) {
        context.dataStore.edit { prefs -> prefs[CURRENT_KEY] = id ?: "" }
    }

    private suspend fun currentId(): String = context.dataStore.data.first()[CURRENT_KEY] ?: ""

    private suspend fun save(devices: List<Device>) {
        val array = JSONArray()
        devices.forEach { array.put(it.toJson()) }
        context.dataStore.edit { prefs -> prefs[DEVICES_KEY] = array.toString() }
    }

    companion object {
        private val DEVICES_KEY = stringPreferencesKey("devices")
        private val CURRENT_KEY = stringPreferencesKey("current_device_id")
    }
}
