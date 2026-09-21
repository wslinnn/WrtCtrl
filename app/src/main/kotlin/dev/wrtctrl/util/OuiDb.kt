package dev.wrtctrl.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * MAC 前缀 → 厂商 本地数据库（客户端页品牌图标，机制对齐 luci-app-oui）。
 * 数据 = assets/oui/vendors.json（底层 WH-2099/macdb，MIT，许可文件随 assets 分发）：
 * `{"vendors":[[slug,显示名],…],"prefixes":{6/7/9位hex前缀: 厂商序号 或 null}}`——
 * 三种前缀长度对应 IEEE MA-S/MA-M/MA-L 三种分配粒度；显式 null = 该前缀未命中
 * （短路，不再尝试更短前缀）。
 */
class OuiDb private constructor(json: JSONObject) {

    /** 厂商：slug 对应 res/drawable 品牌图标（VendorIcons 映射），name 为显示名（可上屏） */
    data class VendorInfo(val slug: String, val name: String)

    private val vendors: JSONArray = json.optJSONArray("vendors") ?: JSONArray()
    private val prefixes: JSONObject = json.optJSONObject("prefixes") ?: JSONObject()

    /**
     * MAC 归一化：剥 `:`/`-` 分隔符并大写；首字节低两位（组播位 + 本地管理位）
     * 任一置位即随机 MAC/组播地址，OUI 不可信 → null（不查询，与 luci-app-oui 同款）
     */
    fun normalize(macRaw: String): String? {
        val mac = macRaw.trim().replace(":", "").replace("-", "").uppercase()
        if (!MAC_FORMAT.matches(mac)) return null
        val first = mac.substring(0, 2).toInt(16)
        return if (first and 0b11 == 0) mac else null
    }

    /** MAC → 厂商；畸形/随机/未命中返回 null（调用方回落通用图标） */
    fun vendorOf(macRaw: String): VendorInfo? {
        val mac = normalize(macRaw) ?: return null
        for (len in PREFIX_LENGTHS) {
            if (mac.length < len) break
            val prefix = mac.take(len)
            if (!prefixes.has(prefix)) continue
            if (prefixes.isNull(prefix)) return null
            val entry = vendors.optJSONArray(prefixes.getInt(prefix)) ?: return null
            val slug = entry.optString(0).takeIf(String::isNotBlank) ?: return null
            return VendorInfo(slug, entry.optString(1))
        }
        return null
    }

    companion object {
        private val MAC_FORMAT = Regex("^[0-9A-F]{12}$")

        /** 前缀查找顺序：MA-S(9) → MA-M(7) → MA-L(6)，长前缀优先更精确 */
        private val PREFIX_LENGTHS = intArrayOf(9, 7, 6)

        private const val ASSET = "oui/vendors.json"

        @Volatile
        private var cached: OuiDb? = null

        /** 进程级单例（IO 线程加载一次）；资产缺失/损坏降级空库——全部查不到走通用图标，不崩 */
        suspend fun get(context: Context): OuiDb = cached ?: withContext(Dispatchers.IO) {
            cached ?: runCatching {
                val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
                OuiDb(JSONObject(text))
            }.getOrElse {
                Log.w("wrtctrl", "oui db load failed: ${it.message}")
                OuiDb(JSONObject())
            }.also { cached = it }
        }

        /** 注入入口（JVM 单测用；畸形输入静默降级空库——不触 android.util.Log 桩） */
        fun fromJson(text: String): OuiDb = try {
            OuiDb(JSONObject(text))
        } catch (e: Exception) {
            OuiDb(JSONObject())
        }
    }
}
