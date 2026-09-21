package dev.wrtctrl.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "wrtctrl")

/**
 * 设备仓储：
 * - 设备列表 + 当前设备 id 存 DataStore；密码经 SecureStore 加密后随 JSON 落盘
 * - CRUD 各一个
 * - 插件安装探测缓存不在此处（UI 层 PluginCatalog 负责）
 *
 * 健壮性：解析失败按空数据处理 + logcat（tag=wrtctrl），绝不让启动路径 boot() 崩溃——
 * 存储损坏时用户可重新添加设备，而不是闪退无出口。
 * 原子性：写操作收敛为单次 edit 事务——读改写在 DataStore
 * 的串行 actor 上完成，并发 add/update 不再互相丢更新；Keystore 加解密随之
 * 离开调用方线程（此前可能在 Main）。
 */
class DeviceRepository(private val context: Context) {

    suspend fun list(): List<Device> = try {
        withContext(Dispatchers.IO) {
            context.dataStore.data.first()[DEVICES_KEY]?.let(::decode) ?: emptyList()
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("wrtctrl", "device store unreadable, treating as empty: ${e.message}")
        emptyList()
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
        upsert { it + device }
        return device
    }

    suspend fun update(device: Device) {
        upsert { list -> list.map { if (it.id == device.id) device else it } }
    }

    suspend fun delete(id: String) {
        // 删设备与清当前指针同一事务，不留「设备已删、current 还指着」的中间态
        context.dataStore.edit { prefs ->
            val devices = try {
                prefs[DEVICES_KEY]?.let(::decode) ?: emptyList()
            } catch (e: Exception) {
                Log.w("wrtctrl", "device store unreadable while deleting: ${e.message}")
                emptyList()
            }
            prefs[DEVICES_KEY] = encode(devices.filterNot { it.id == id })
            if (prefs[CURRENT_KEY] == id) prefs[CURRENT_KEY] = ""
        }
    }

    suspend fun get(id: String): Device? = list().find { it.id == id }

    fun currentFlow(): Flow<Device?> = context.dataStore.data.map { prefs ->
        try {
            val id = prefs[CURRENT_KEY] ?: return@map null
            val json = prefs[DEVICES_KEY] ?: return@map null
            JSONArray(json)
                .let { array -> (0 until array.length()).map { array.getJSONObject(it) } }
                .find { it.optString("id") == id }
                ?.let { Device.fromJson(it) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("wrtctrl", "current device unreadable: ${e.message}")
            null
        }
    }

    suspend fun current(): Device? = withContext(Dispatchers.IO) { currentFlow().first() }

    suspend fun setCurrent(id: String?) {
        context.dataStore.edit { prefs -> prefs[CURRENT_KEY] = id ?: "" }
    }

    /** 读-改-写单事务：transform 在 DataStore 串行 actor 上执行（加密同线程） */
    private suspend fun upsert(transform: (List<Device>) -> List<Device>) {
        context.dataStore.edit { prefs ->
            val devices = try {
                prefs[DEVICES_KEY]?.let(::decode) ?: emptyList()
            } catch (e: Exception) {
                Log.w("wrtctrl", "device store unreadable, treating as empty: ${e.message}")
                emptyList()
            }
            prefs[DEVICES_KEY] = encode(transform(devices))
        }
    }

    /** JSON → 设备列表（损坏抛错，由调用方按空表降级 */
    private fun decode(json: String): List<Device> {
        val array = JSONArray(json)
        return (0 until array.length()).map { Device.fromJson(array.getJSONObject(it)) }
    }

    private fun encode(devices: List<Device>): String {
        val array = JSONArray()
        devices.forEach { array.put(it.toJson()) }
        return array.toString()
    }

    companion object {
        private val DEVICES_KEY = stringPreferencesKey("devices")
        private val CURRENT_KEY = stringPreferencesKey("current_device_id")
    }
}
