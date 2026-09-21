package dev.wrtctrl.viewmodel

import org.json.JSONObject

data class IfaceInfo(
    val name: String,
    val proto: String?,
    val l3Device: String?,
    /** UP/DOWN 徽章（DOWN 接口保留并显示，附诊断入口） */
    val up: Boolean,
    val mac: String?,
    val rxBytes: Long,
    val txBytes: Long,
    val ipv4: String?,
    val ipv6Addrs: List<String>,
    /** 委派前缀（ipv6-prefix[]）：只进 IPv6 详情弹窗分节，不单独成行 */
    val ipv6Prefix: List<String>,
    /** IPv6-PD 分配（ipv6-prefix-assignment：local-address 优先回落 address，附 /mask） */
    val ipv6PdAssign: List<String>,
    val gateway: String?,
    val dns: List<String>,
)

data class NetDeviceInfo(
    val name: String,
    val devtype: String?,
    val up: Boolean,
    val mac: String?,
    val mtu: Int?,
    val ports: List<String>,
    val rxBytes: Long,
    val txBytes: Long,
    val rxPackets: Long,
    val txPackets: Long,
)

data class DeviceGroup(val type: String, val devices: List<NetDeviceInfo>)

data class WifiEncryption(
    val enabled: Boolean,
    val wpa: List<Int>,
    val authentication: List<String>,
    val ciphers: List<String>,
)

data class WifiIface(
    val ifname: String,
    /** uci config.network 列表——与 uci section 的 network 交集做关联 */
    val networks: List<String>,
    /** uci wifi-iface section 名（netifd status interfaces[].section；无线编辑器跳转目标） */
    val section: String? = null,
    val ssid: String?,
    val mode: String?,
    val bssid: String?,
    val signal: Int?,
    /** iwinfo.bitrate，单位 kbit/s */
    val bitrate: Double?,
    val encryption: WifiEncryption?,
)

data class RadioInfo(
    val name: String,
    /** config.band 大写（5G/2G…），缺 null 隐藏行 */
    val band: String?,
    val chip: String?,
    /** iwinfo.channel，回落 config.channel */
    val channel: String?,
    /** hwmodes_text 回落 hwmodes[] join("/") 大写；协议行 = "802.11" + 它 */
    val hwmodes: String?,
    /** uci disabled=='1'（radio 组条启停开关态；uci 读取失败缺省 false=启用） */
    val disabled: Boolean = false,
    val ifaces: List<WifiIface>,
)

/** SSID 密码凭据（从 uci wireless 拉取；仅内存，不落盘） */
data class WifiSecret(
    val ssid: String?,
    /** uci 原始 encryption 值（none/psk2/sae/wpa2…），判定个人网与 WIFI 串 type */
    val encryption: String?,
    /** 明文密码；开放网 = null */
    val key: String?,
)

/**
 * 网络页三个 ubus 响应的纯解析：JSONObject 进、
 * 结构化模型出，无 Android 依赖，JVM 单测覆盖。解析失败返回空/默认值（
 * 拉取失败显空态，错误链不上 UI）；死字段（接口 uptime/包数、link.speed、
 * iwinfo.noise）一律不进模型。
 */
internal object NetworkParsers {

    /** 接口卡列表：排序 wan 置顶（默认路由主卡）、lan 次之、其余按名；
     *  loopback 剔除（零信息量，与首页接口 chips 同口径）。DOWN 保留（带 up 标志——
     *  异常状态带下一步）；MAC 与收发流量按 l3_device 从 getNetworkDevices 结果补齐。
     *  getNetworkDevices 的响应是以设备名为键的根对象（响应按设备名键控
     *  直接取键），兼容个别固件包一层 result 的形态 */
    fun ifaceList(dump: JSONObject, devices: JSONObject): List<IfaceInfo> {
        val deviceMap = devices.optJSONObject("result") ?: devices
        val arr = dump.optJSONArray("interface") ?: return emptyList()
        return (0 until arr.length())
            .mapNotNull { i -> arr.optJSONObject(i) }
            .filter { it.optString("interface") != "loopback" }
            .map { entry ->
                val l3 = entry.optString("l3_device").takeIf(String::isNotBlank)
                val dev = l3?.let { deviceMap?.optJSONObject(it) }
                IfaceInfo(
                    name = entry.optString("interface"),
                    proto = entry.optString("proto").takeIf(String::isNotBlank),
                    l3Device = l3,
                    up = entry.optBoolean("up", true),
                    mac = dev?.optString("mac")?.takeIf { it.isNotBlank() },
                    rxBytes = dev?.optJSONObject("stats")?.optLong("rx_bytes") ?: 0L,
                    txBytes = dev?.optJSONObject("stats")?.optLong("tx_bytes") ?: 0L,
                    ipv4 = entry.optJSONArray("ipv4-address")?.optJSONObject(0)?.let { v4 ->
                        v4.optString("address").takeIf(String::isNotBlank)
                            ?.let { addr -> "$addr/${v4.optString("mask")}" }
                    },
                    ipv6Addrs = addrList(entry, "ipv6-address", withUlaLast = true),
                    ipv6Prefix = addrList(entry, "ipv6-prefix", withUlaLast = false),
                    ipv6PdAssign = pdAssignList(entry),
                    gateway = gatewayOf(entry),
                    dns = stringList(entry, "dns-server"),
                )
            }
            .sortedWith(compareBy({ it.name != "wan" }, { it.name != "lan" }, { it.name }))
    }

    /** 设备分组：按 devtype 归 bridge/ethernet/wireless/vlan/tunnel，未知归 other；
     *  过滤显式 down 与 lo。组序固定 bridge→ethernet→wireless→vlan→tunnel→other
     *  （org.json 对象键为 HashMap 序，固件原始顺序不可恢复，故不按"首见序"）；
     *  ethernet 组内 eth* 前缀优先；组内按名排序保证确定性 */
    fun deviceGroups(devices: JSONObject): List<DeviceGroup> {
        val map = devices.optJSONObject("result") ?: devices
        val buckets = linkedMapOf<String, MutableList<NetDeviceInfo>>()
        for (key in map.keys()) {
            val dev = map.optJSONObject(key) ?: continue
            if (dev.optBoolean("up", true) == false) continue
            if (key == "lo") continue
            val info = NetDeviceInfo(
                name = dev.optString("name").ifBlank { key },
                devtype = dev.optString("devtype").takeIf { it.isNotBlank() },
                up = dev.optBoolean("up", true),
                mac = dev.optString("mac").takeIf { it.isNotBlank() },
                mtu = dev.optInt("mtu").takeIf { it > 0 },
                ports = stringList(dev, "ports"),
                rxBytes = dev.optJSONObject("stats")?.optLong("rx_bytes") ?: 0L,
                txBytes = dev.optJSONObject("stats")?.optLong("tx_bytes") ?: 0L,
                rxPackets = dev.optJSONObject("stats")?.optLong("rx_packets") ?: 0L,
                txPackets = dev.optJSONObject("stats")?.optLong("tx_packets") ?: 0L,
            )
            val type = when (info.devtype) {
                "bridge", "ethernet", "wireless", "vlan", "tunnel" -> info.devtype!!
                else -> "other"
            }
            buckets.getOrPut(type) { mutableListOf() } += info
        }
        return listOf("bridge", "ethernet", "wireless", "vlan", "tunnel", "other")
            .filter { it in buckets }
            .map { type ->
                val list = buckets[type]!!
                val sorted = if (type == "ethernet") {
                    list.sortedWith(compareBy({ !it.name.startsWith("eth") }, { it.name }))
                } else {
                    list.sortedBy { it.name }
                }
                DeviceGroup(type, sorted)
            }
    }

    /** 无线：luci-rpc getWirelessDevices 返回以 radio 名为键的对象（键即卡头名），
     *  兼容包一层 result 的形态 */
    fun radios(payload: JSONObject): List<RadioInfo> {
        val root = payload.optJSONObject("result") ?: payload
        return root.keys().asSequence().mapNotNull { key ->
            val radio = root.optJSONObject(key) ?: return@mapNotNull null
            val iw = radio.optJSONObject("iwinfo")
            val config = radio.optJSONObject("config")
            val ifaces = radio.optJSONArray("interfaces")?.let { list ->
                (0 until list.length()).mapNotNull { j ->
                    val ifEntry = list.optJSONObject(j) ?: return@mapNotNull null
                    val ifIw = ifEntry.optJSONObject("iwinfo")
                    WifiIface(
                        ifname = ifEntry.optString("ifname"),
                        networks = ifEntry.optJSONObject("config")?.let { stringList(it, "network") } ?: emptyList(),
                        section = ifEntry.optString("section").takeIf(String::isNotBlank),
                        ssid = ifIw?.optString("ssid")?.takeIf(String::isNotBlank),
                        // iwinfo.mode（Master/Client）优先，config.mode（ap/sta）回落
                        mode = ifIw?.optString("mode")?.takeIf(String::isNotBlank)
                            ?: ifEntry.optJSONObject("config")?.optString("mode")?.takeIf(String::isNotBlank),
                        bssid = ifIw?.optString("bssid")?.takeIf(String::isNotBlank),
                        signal = ifIw?.takeIf { it.has("signal") }?.optInt("signal"),
                        bitrate = ifIw?.takeIf { it.has("bitrate") }?.optDouble("bitrate")?.takeIf { !it.isNaN() },
                        encryption = ifIw?.optJSONObject("encryption")?.let(::parseEncryption),
                    )
                }
            } ?: emptyList()
            RadioInfo(
                name = key,
                band = config?.optString("band")?.takeIf(String::isNotBlank)?.uppercase(),
                chip = iw?.optJSONObject("hardware")?.optString("name")?.takeIf(String::isNotBlank),
                channel = (iw?.takeIf { it.has("channel") }?.optInt("channel") ?: config?.takeIf { it.has("channel") }?.optInt("channel"))
                    ?.takeIf { it >= 0 }?.toString(),
                hwmodes = iw?.optString("hwmodes_text")?.takeIf(String::isNotBlank)
                    ?: iw?.optJSONArray("hwmodes")?.let { modes ->
                        (0 until modes.length()).mapNotNull { modes.optString(it).takeIf(String::isNotBlank) }
                            .joinToString(", ").uppercase().takeIf(String::isNotEmpty)
                    },
                ifaces = ifaces,
            )
        }.sortedBy { it.name }.toList()
    }

    /**
     * radio 启停态（无线 Tab 组条开关）：uciGet("wireless") 的 wifi-device 段名 → disabled=='1'。
     * 独立于 getWirelessDevices（netifd status 不含该 uci option）；段缺失/未设置 = 启用（false）。
     */
    fun radioDisabled(uciData: JSONObject): Map<String, Boolean> {
        val out = mutableMapOf<String, Boolean>()
        for (key in uciData.keys()) {
            val section = uciData.optJSONObject(key) ?: continue
            if (section.optString("section_type") != "wifi-device") continue
            val options = section.optJSONObject("options") ?: continue
            val raw = options.optJSONArray("disabled")?.optString(0) ?: options.optString("disabled")
            out[key] = raw == "1"
        }
        return out
    }

    /**
     * 加密串（要点）：未加密 → noEncryption；否则 WPA 版本映射（1/2/3 → WPA/WPA2/WPA3，
     * + 连接）与认证方式（大写 + 连接）空格拼接，ciphers 非空再追加 " (CCMP/GCMP)"；
     * 三者全空回落 encrypted。两段回落文案由调用方注入本地化资源。
     */
    fun encryptionLabel(enc: WifiEncryption?, noEncryption: String, encrypted: String): String {
        if (enc == null || !enc.enabled) return noEncryption
        val versions = enc.wpa.mapNotNull { v ->
            when (v) { 1 -> "WPA"; 2 -> "WPA2"; 3 -> "WPA3"; else -> null }
        }.joinToString("+")
        val auth = enc.authentication.filter(String::isNotBlank).joinToString("+") { it.uppercase() }
        val head = listOf(versions, auth).filter(String::isNotEmpty).joinToString(" ")
        val ciphers = enc.ciphers.filter(String::isNotBlank).joinToString("/") { it.uppercase() }
        return buildString {
            append(head)
            if (ciphers.isNotEmpty()) {
                if (isNotEmpty()) append(' ')
                append("($ciphers)")
            }
        }.ifEmpty { encrypted }
    }

    /** SSID 凭据（uci wireless）：WrtCore.uciGet 类型化 section 表。section ↔ 运行时 iface 的
     *  关联与 LuCI getStatus 语义一致：section 自带 ifname 精确匹配优先，否则 network
     *  列表交集（且 section.device = 当前 radio）。
     *  读 options 的 ssid/encryption/key（key 空 = 开放网）。null = 无关联 section */
    fun wifiSecret(
        sections: JSONObject,
        radioName: String,
        ifname: String,
        networks: List<String>,
    ): WifiSecret? {
        val candidates = sections.keys().asSequence()
            .mapNotNull { sections.optJSONObject(it) }
            .filter { it.optString("section_type") == "wifi-iface" }
            .mapNotNull { it.optJSONObject("options") }
            .filter { it.optString("device") == radioName }
            .toList()
        // 旧 wireless.js 同款：section 自带 ifname 精确匹配优先，否则 network 列表交集
        candidates.firstOrNull { options ->
            options.optString("ifname").takeIf(String::isNotBlank) == ifname ||
                networks.any { it in stringList(options, "network") }
        }?.let { return toWifiSecret(it) }
        // 兜底（MTK/mtwifi 固件 section 常无 ifname/network 关联字段）：该 radio 下唯一的
        // wifi-iface 视为同一 SSID——确定性关联而非近似；多候选无法消歧则仍判失败
        return candidates.singleOrNull()?.let { toWifiSecret(it) }
    }

    private fun toWifiSecret(options: JSONObject): WifiSecret = WifiSecret(
        ssid = options.optString("ssid").takeIf(String::isNotBlank),
        encryption = options.optString("encryption").takeIf(String::isNotBlank),
        key = options.optString("key").takeIf(String::isNotBlank),
    )

    /** JSON 字符串数组 → 非空串列表（缺键返回空列表） */
    private fun stringList(obj: JSONObject, key: String): List<String> {
        val arr = obj.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
    }

    /** iwinfo.encryption → 结构化（字段缺失给空默认，加密串由 encryptionLabel 组装） */
    private fun parseEncryption(enc: JSONObject): WifiEncryption = WifiEncryption(
        enabled = enc.optBoolean("enabled"),
        wpa = intList(enc, "wpa"),
        authentication = stringList(enc, "authentication"),
        ciphers = stringList(enc, "ciphers"),
    )

    private fun intList(obj: JSONObject, key: String): List<Int> {
        val arr = obj.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).map { arr.optInt(it) }
    }

    /** 地址列表（address/mask 拼接）；withUlaLast=true 时 fd* 前缀（ULA）后置 */
    private fun addrList(entry: JSONObject, key: String, withUlaLast: Boolean): List<String> {
        val arr = entry.optJSONArray(key) ?: return emptyList()
        val list = (0 until arr.length()).mapNotNull { i ->
            val item = arr.optJSONObject(i) ?: return@mapNotNull null
            val address = item.optString("address").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val mask = item.optString("mask")
            if (mask.isBlank()) address else "$address/$mask"
        }
        return if (withUlaLast) {
            list.sortedBy { it.startsWith("fd") }
        } else {
            list
        }
    }

    /** IPv6-PD 分配：优先 local-address（固件两种形态：字符串 或 {address,mask} 对象，
     *  对象形态解析失败时），回落 address（/mask） */
    private fun pdAssignList(entry: JSONObject): List<String> {
        val arr = entry.optJSONArray("ipv6-prefix-assignment") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val item = arr.optJSONObject(i) ?: return@mapNotNull null
            val local = when (val la = item.opt("local-address")) {
                is String -> la.takeIf(String::isNotBlank)
                is JSONObject -> la.optString("address").takeIf(String::isNotBlank)
                else -> null
            }
            val address = local ?: item.optString("address").takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val maskSource = item.optJSONObject("local-address") ?: item
            val mask = maskSource.optString("mask")
            if (mask.isBlank()) address else "$address/$mask"
        }
    }

    /** 默认路由网关：route[] 中 target=0.0.0.0 且 mask=0 的 nexthop */
    private fun gatewayOf(entry: JSONObject): String? {
        val routes = entry.optJSONArray("route") ?: return null
        for (i in 0 until routes.length()) {
            val route = routes.optJSONObject(i) ?: continue
            if (route.optString("target") == "0.0.0.0" && route.optInt("mask") == 0) {
                return route.optString("nexthop").takeIf(String::isNotBlank)
            }
        }
        return null
    }
}
