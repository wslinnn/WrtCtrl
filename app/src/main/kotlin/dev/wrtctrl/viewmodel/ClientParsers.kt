package dev.wrtctrl.viewmodel

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class RateInfo(
    /** kbit/s */
    val rate: Double?,
    val mhz: Int?,
    val ht: Boolean,
    val vht: Boolean,
    val he: Boolean,
    val eht: Boolean,
    val mcs: Int?,
)

data class WifiClient(
    val mac: String,
    val signal: Int,
    val connectedTime: Long,
    val rx: RateInfo?,
    val tx: RateInfo?,
    val ifname: String,
    /** 所属 radio 的 config.band 大写（如 5G），无则 null */
    val band: String?,
    /** DHCP 租约合并出的主机名（v4 先、v6 后覆盖） */
    val hostname: String?,
    /** DHCP v4 租约合并出的 IPv4（无租约 = null，不猜） */
    val ip: String?,
)

data class DhcpLease(
    val hostname: String?,
    val macaddr: String?,
    val ip: String,
    val duid: String?,
    /** 剩余租期秒数 */
    val expires: Long,
)

/** 静态租约条目（uci dhcp @host 全量）：section 为删除主键；name/ip 可空（数据有源，缺失不猜）。
 *  mac 大写域（isStatic 判定口径；区别于 blockedMacs 的小写域） */
data class StaticHost(val section: String, val mac: String, val name: String?, val ip: String?)

/**
 * 客户端页纯解析：JSONObject/JSONArray 进、结构化模型出，
 * JVM 单测覆盖；失败一律空/默认值（ 静默空态）。
 */
internal object ClientParsers {

    /** 拉黑规则的 uci name 前缀（写入与识别共用，见 blockedMacs） */
    const val BLOCK_RULE_PREFIX = "block_"

    /** 无线接口清单：getWirelessDevices（名称键控根对象）→ (ifname, band 大写或 null) 列表 */
    fun wifiIfaces(payload: JSONObject): List<Pair<String, String?>> {
        val root = payload.optJSONObject("result") ?: payload
        val result = mutableListOf<Pair<String, String?>>()
        for (key in root.keys()) {
            val radio = root.optJSONObject(key) ?: continue
            val band = radio.optJSONObject("config")?.optString("band")
                ?.takeIf(String::isNotBlank)?.uppercase()
            val ifaces = radio.optJSONArray("interfaces") ?: continue
            for (i in 0 until ifaces.length()) {
                val ifname = ifaces.optJSONObject(i)?.optString("ifname").orEmpty()
                if (ifname.isNotBlank()) result += ifname to band
            }
        }
        // org.json 键序为 HashMap 序，按接口名排序保证确定性（真实 ifname 如 phy0-ap0 天然有序）
        return result.sortedBy { it.first }
    }

    /** 一个无线接口的关联终端：assoclist results 数组 + hostname/IP 合并表 → 客户端列表 */
    fun clientsOf(
        ifname: String,
        band: String?,
        results: JSONArray,
        hostnames: Map<String, String>,
        ips: Map<String, String>,
    ): List<WifiClient> {
        return (0 until results.length()).mapNotNull { i ->
            val entry = results.optJSONObject(i) ?: return@mapNotNull null
            val mac = entry.optString("mac").takeIf(String::isNotBlank) ?: return@mapNotNull null
            WifiClient(
                mac = mac,
                signal = entry.optInt("signal"),
                connectedTime = entry.optLong("connected_time"),
                rx = entry.optJSONObject("rx")?.let(::parseRate),
                tx = entry.optJSONObject("tx")?.let(::parseRate),
                ifname = ifname,
                band = band,
                hostname = hostnames[mac.uppercase()],
                ip = ips[mac.uppercase()],
            )
        }
    }

    /** macaddr 大写 → IPv4（租约 IP 是权威来源；无租约不猜） */
    fun ipMap(v4: List<DhcpLease>): Map<String, String> {
        val map = mutableMapOf<String, String>()
        v4.forEach { lease ->
            val mac = lease.macaddr?.uppercase() ?: return@forEach
            if (lease.ip.isNotBlank()) map[mac] = lease.ip
        }
        return map
    }

    /** 静态租约全量（WrtCore.uciGet("dhcp") 类型化 section 表）：isStatic 判定、名称反查、
     *  「静态租约」组展示与删除（section 主键）同源于此列表；按 MAC 排序保证确定性 */
    fun staticHosts(uciDhcp: JSONObject): List<StaticHost> =
        uciDhcp.keys().asSequence()
            .mapNotNull { key -> uciDhcp.optJSONObject(key)?.let { key to it } }
            .filter { (_, section) -> section.optString("section_type") == "host" }
            .mapNotNull { (key, section) ->
                val options = section.optJSONObject("options") ?: return@mapNotNull null
                val mac = options.optString("mac").uppercase().takeIf(String::isNotBlank) ?: return@mapNotNull null
                StaticHost(
                    section = key,
                    mac = mac,
                    name = options.optString("name").takeIf(String::isNotBlank),
                    ip = options.optString("ip").takeIf(String::isNotBlank),
                )
            }
            .sortedBy { it.mac }
            .toList()

    /**
     * 拉黑规则集合（WrtCore.uciGet("firewall") 类型化 section 表）：归一化 MAC（小写）→
     * uci section 名（解除需按名删除）。识别约定 = name 以 [BLOCK_RULE_PREFIX] 开头
     * （本 app 写入的命名，用户手写规则无此前缀不会被误读/误删）；src_mac 兼容
     * string 与 list 两种 uci 形态。
     */
    fun blockedMacs(uciFirewall: JSONObject): Map<String, String> =
        uciFirewall.keys().asSequence()
            .mapNotNull { key -> uciFirewall.optJSONObject(key)?.let { key to it } }
            .filter { (_, section) -> section.optString("section_type") == "rule" }
            .mapNotNull { (key, section) ->
                val options = section.optJSONObject("options") ?: return@mapNotNull null
                if (!options.optString("name").startsWith(BLOCK_RULE_PREFIX)) return@mapNotNull null
                key to blockedMacsOf(options)
            }
            .flatMap { (key, macs) -> macs.map { mac -> mac.lowercase() to key } }
            .toMap()

    /** options 里的 src_mac：uci list（数组）逐个收录，否则按单值 string */
    private fun blockedMacsOf(options: JSONObject): List<String> =
        options.optJSONArray("src_mac")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
        } ?: listOfNotNull(options.optString("src_mac").takeIf(String::isNotBlank))

    /** getDHCPLeases → (v4, v6) 两个租约列表 */
    fun dhcpLeases(payload: JSONObject): Pair<List<DhcpLease>, List<DhcpLease>> {
        val v4 = leaseList(payload, "dhcp_leases", ipKey = "ipaddr", withDuid = false)
        val v6 = leaseList(payload, "dhcp6_leases", ipKey = "ip6addr", withDuid = true)
        return v4 to v6
    }

    /** macaddr 大写 → hostname；v4 先写入、v6 后写入覆盖 */
    fun hostnameMap(v4: List<DhcpLease>, v6: List<DhcpLease>): Map<String, String> {
        val map = mutableMapOf<String, String>()
        (v4 + v6).forEach { lease ->
            val mac = lease.macaddr?.uppercase() ?: return@forEach
            lease.hostname?.takeIf(String::isNotBlank)?.let { map[mac] = it }
        }
        return map
    }

    /**
     * 速率串（与 LuCI 速率格式一致）：`(rate/1000) 一位小数 Mbit/s` + ` NMHz` +
     * ` {ht|vht|he|eht}-mcsN`——类型取 ht>vht>he>eht 首个为真，mcs 需为数字且类型存在；
     * 无 rate 显示 `- Mbit/s`
     */
    fun rateText(info: RateInfo?): String {
        if (info == null) return "- Mbit/s"
        val base = info.rate?.let { String.format(Locale.US, "%.1f Mbit/s", it / 1000.0) } ?: "- Mbit/s"
        val parts = mutableListOf(base)
        info.mhz?.let { parts += "${it}MHz" }
        val type = when {
            info.ht -> "ht"
            info.vht -> "vht"
            info.he -> "he"
            info.eht -> "eht"
            else -> null
        }
        if (type != null && info.mcs != null) parts += "$type-mcs${info.mcs}"
        return parts.joinToString(" ")
    }

    private fun parseRate(obj: JSONObject): RateInfo = RateInfo(
        rate = obj.takeIf { it.has("rate") }?.optDouble("rate")?.takeIf { !it.isNaN() },
        mhz = obj.takeIf { it.has("mhz") }?.optInt("mhz"),
        ht = obj.optBoolean("ht"),
        vht = obj.optBoolean("vht"),
        he = obj.optBoolean("he"),
        eht = obj.optBoolean("eht"),
        mcs = obj.takeIf { it.has("mcs") }?.optInt("mcs"),
    )

    private fun leaseList(payload: JSONObject, key: String, ipKey: String, withDuid: Boolean): List<DhcpLease> {
        val arr = payload.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val entry = arr.optJSONObject(i) ?: return@mapNotNull null
            DhcpLease(
                hostname = entry.optString("hostname").takeIf(String::isNotBlank),
                macaddr = entry.optString("macaddr").takeIf(String::isNotBlank),
                ip = entry.optString(ipKey),
                duid = if (withDuid) entry.optString("duid").takeIf(String::isNotBlank) else null,
                expires = entry.optLong("expires"),
            )
        }
    }
}
