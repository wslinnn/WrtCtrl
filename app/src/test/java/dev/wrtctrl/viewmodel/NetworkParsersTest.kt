package dev.wrtctrl.viewmodel

import dev.wrtctrl.util.Format
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkParsersTest {

    @Test
    fun `wifiSecret 关联规则——ifname 精确优先、network 交集、单候选兜底`() {
        // WrtCore.uciGet 形态：键 = section 名，选项在 options 内；关联与 LuCI getStatus 语义一致
        val sections = JSONObject(
            """
            {"default_radio0":{"name":"default_radio0","section_type":"wifi-iface","anonymous":false,
              "options":{"device":"radio0","ifname":"wlan0","network":["lan"],"ssid":"ASUS_5G","encryption":"psk2","key":"s3cret;pass"}},
             "default_radio1":{"name":"default_radio1","section_type":"wifi-iface","anonymous":false,
              "options":{"device":"radio1","network":["guest"],"ssid":"Guest","encryption":"none"}},
             "default_radio1b":{"name":"default_radio1b","section_type":"wifi-iface","anonymous":false,
              "options":{"device":"radio1","network":["guest2"],"ssid":"Guest2","encryption":"psk2","key":"k2"}},
             "wg0":{"name":"wg0","section_type":"interface","anonymous":false,"options":{}}}
            """.trimIndent(),
        )
        // section 自带 ifname：精确匹配
        val s0 = NetworkParsers.wifiSecret(sections, "radio0", "wlan0", listOf("lan"))!!
        assertEquals("ASUS_5G", s0.ssid)
        assertEquals("psk2", s0.encryption)
        assertEquals("s3cret;pass", s0.key)
        // section 无 ifname：network 交集 + 同 radio 关联（多候选可消歧）
        val s1 = NetworkParsers.wifiSecret(sections, "radio1", "wlan1", listOf("guest"))!!
        assertEquals("Guest", s1.ssid)
        assertNull(s1.key)
        // MTK/mtwifi：section 无 ifname/network 关联字段——radio 下唯一候选兜底
        val lone = JSONObject(
            """
            {"default_rax0":{"name":"default_rax0","section_type":"wifi-iface","anonymous":false,
              "options":{"device":"rax0","ssid":"MTK","encryption":"psk2","key":"kk"}}}
            """.trimIndent(),
        )
        assertEquals("MTK", NetworkParsers.wifiSecret(lone, "rax0", "rax0", emptyList())!!.ssid)
        // 多候选无法消歧（network 不交集）→ 失败
        assertNull(NetworkParsers.wifiSecret(sections, "radio1", "unknown", emptyList()))
        // 非 wifi-iface section 不参与
        assertNull(NetworkParsers.wifiSecret(sections, "wg0", "wlan0", listOf("lan")))
    }

    @Test
    fun `接口解析——wan 置顶 lan 次之、loopback 剔除、设备表补 MAC 流量、ULA 后置、PD 双形态`() {
        val dump = JSONObject(
            """
            {"interface":[
              {"interface":"lan","up":true,"proto":"static","l3_device":"br-lan",
               "ipv4-address":[{"address":"192.168.2.1","mask":"255.255.255.0"}],
               "ipv6-address":[{"address":"2409:8154::1","mask":"64"},{"address":"fd00::1","mask":"64"}],
               "ipv6-prefix":[{"address":"2409:8154:aa::","mask":"56"}],
               "ipv6-prefix-assignment":[{"address":"2409:8154:aa::","mask":"56"},
                                         {"local-address":{"address":"2409:8154:aa:1::1","mask":"64"}}],
               "dns-server":["192.168.2.1","fe80::1"]},
              {"interface":"loopback","up":true,"proto":"static","l3_device":"lo","ipv4-address":[],"route":[],"dns-server":[]},
              {"interface":"wan6","up":false,"proto":"dhcpv6","l3_device":"eth0.2"},
              {"interface":"wan","up":true,"proto":"pppoe","l3_device":"eth0.2","route":[],"dns-server":[]}
            ]}
            """.trimIndent(),
        )
        // 真实形态：getNetworkDevices 响应本身即以设备名为键的根对象
        val devices = JSONObject(
            """
            {"br-lan":{"name":"br-lan","mac":"AA:BB:CC:DD:EE:FF","up":true,
              "stats":{"rx_bytes":1024,"tx_bytes":2048,"rx_packets":10,"tx_packets":20}}}
            """.trimIndent(),
        )
        val ifaces = NetworkParsers.ifaceList(dump, devices)
        // 排序：wan 置顶、lan 次之、其余按名；loopback 剔除；DOWN 保留（带 up 标志）
        assertEquals(listOf("wan", "lan", "wan6"), ifaces.map { it.name })
        assertEquals(false, ifaces[2].up)
        assertEquals(true, ifaces[0].up)
        val lan = ifaces[1]
        assertEquals("AA:BB:CC:DD:EE:FF", lan.mac)
        assertEquals(1024L, lan.rxBytes)
        assertEquals("192.168.2.1/255.255.255.0", lan.ipv4)
        // ULA（fd*）后置
        assertEquals(listOf("2409:8154::1/64", "fd00::1/64"), lan.ipv6Addrs)
        assertEquals(listOf("2409:8154:aa::/56"), lan.ipv6Prefix)
        // PD 分配：local-address 兼容字符串与 {address,mask} 对象两种固件形态，无 local-address 回落 address
        assertEquals(listOf("2409:8154:aa::/56", "2409:8154:aa:1::1/64"), lan.ipv6PdAssign)
        assertEquals(listOf("192.168.2.1", "fe80::1"), lan.dns)
        assertNull(lan.gateway)
        assertNull(ifaces[2].mac) // eth0.2 不在设备表
    }

    @Test
    fun `设备分组——bridge 最前 other 最后、down 与 lo 过滤、ethernet 组内 eth 优先`() {
        // result 包装形态（兼容分支；真实形态为根对象，见接口解析用例）
        val devices = JSONObject(
            """
            {"result":{
              "lo":{"name":"lo","up":true,"devtype":"loopback"},
              "eth1":{"name":"eth1","up":true,"devtype":"ethernet"},
              "eth0":{"name":"eth0","up":true,"devtype":"ethernet"},
              "phy1-ap0":{"name":"phy1-ap0","up":true,"devtype":"wireless"},
              "br-lan":{"name":"br-lan","up":true,"devtype":"bridge","ports":["eth0","eth1"]},
              "wg0":{"name":"wg0","up":true,"devtype":"tunnel"},
              "wan":{"name":"wan","up":false,"devtype":"ethernet"},
              "dsa0":{"name":"dsa0","up":true,"devtype":"dsa"}
            }}
            """.trimIndent(),
        )
        val groups = NetworkParsers.deviceGroups(devices)
        assertEquals(listOf("bridge", "ethernet", "wireless", "tunnel", "other"), groups.map { it.type })
        assertEquals(listOf("eth0", "eth1"), groups[1].devices.map { it.name }) // eth* 优先
        assertEquals(listOf("dsa0"), groups[4].devices.map { it.name }) // 未知 devtype 归 other
    }

    @Test
    fun `无线解析——radio 名取键、iwinfo 的 mode 优先、hwmodes 回落逗号连接`() {
        // 真实形态：getWirelessDevices 响应即以 radio 名为键的根对象
        val payload = JSONObject(
            """
            {"radio0":{
              "config":{"band":"5g","channel":36},
              "iwinfo":{"hardware":{"name":"MediaTek MT7981"},"channel":149,
                "hwmodes_text":"a/n/ac/ax",
                "ssid":"Main","bssid":"11:22:33:44:55:66","signal":-52,"bitrate":866700,"mode":"Master",
                "encryption":{"enabled":true,"wpa":[2,3],"authentication":["sae","psk"],"ciphers":["ccmp"]}},
              "interfaces":[
                {"ifname":"phy0-ap0","section":"default_radio0","config":{"mode":"ap"},"iwinfo":{"ssid":"Main","bssid":"11:22:33:44:55:66",
                  "signal":-52,"bitrate":866700,"mode":"Master",
                  "encryption":{"enabled":true,"wpa":[2,3],"authentication":["sae","psk"],"ciphers":["ccmp"]}}}
              ]},
             "radio1":{
              "config":{"band":"2g"},
              "iwinfo":{"channel":6,"hwmodes":["b","g","n"]},
              "interfaces":[]}}
            """.trimIndent(),
        )
        val radios = NetworkParsers.radios(payload)
        assertEquals(listOf("radio0", "radio1"), radios.map { it.name })
        val radio = radios[0]
        assertEquals("5G", radio.band)
        assertEquals("MediaTek MT7981", radio.chip)
        assertEquals("149", radio.channel) // iwinfo 优先于 config
        assertEquals("a/n/ac/ax", radio.hwmodes) // hwmodes_text 原样
        val iface = radio.ifaces[0]
        assertEquals("Master", iface.mode) // iwinfo.mode 优先，config.mode（ap/sta）回落
        assertEquals("default_radio0", iface.section) // uci 段名（无线编辑器跳转目标）
        assertEquals(-52, iface.signal)
        // hwmodes[] 回落路径：逗号+空格连接后大写（对齐旧 formatHwModes）
        assertEquals("B, G, N", radios[1].hwmodes)
        // JUnit4 无 delta 的 assertEquals(double,double) 已无条件抛错，双精度断言必须带 delta
        assertEquals(866700.0, iface.bitrate!!, 0.0)
    }

    @Test
    fun `radio 启停态——wifi-device 段 disabled 提取、缺失段与未设置段回false、list 形态兼容`() {
        val sections = JSONObject(
            """
            {"radio0":{"name":"radio0","section_type":"wifi-device","anonymous":false,
              "options":{"type":"mac80211","band":"5g","disabled":"1"}},
             "radio1":{"name":"radio1","section_type":"wifi-device","anonymous":false,
              "options":{"type":"mtwifi","band":"2g"}},
             "radio2":{"name":"radio2","section_type":"wifi-device","anonymous":false,
              "options":{"disabled":["1"]}},
             "wifinet1":{"name":"wifinet1","section_type":"wifi-iface","anonymous":false,
              "options":{"disabled":"1"}}}
            """.trimIndent(),
        )
        val disabled = NetworkParsers.radioDisabled(sections)
        assertEquals(mapOf("radio0" to true, "radio1" to false, "radio2" to true), disabled)
    }

    @Test
    fun `加密串三态——未加密、版本+认证+ciphers、全空回落`() {
        assertEquals(
            "NO-ENC",
            NetworkParsers.encryptionLabel(WifiEncryption(false, emptyList(), emptyList(), emptyList()), "NO-ENC", "ENC"),
        )
        assertEquals(
            "WPA2+WPA3 SAE+PSK (CCMP/GCMP)",
            NetworkParsers.encryptionLabel(
                WifiEncryption(true, listOf(2, 3), listOf("sae", "psk"), listOf("ccmp", "gcmp")),
                "NO-ENC",
                "ENC",
            ),
        )
        assertEquals(
            "WPA2 PSK",
            NetworkParsers.encryptionLabel(WifiEncryption(true, listOf(2), listOf("psk"), emptyList()), "NO-ENC", "ENC"),
        )
        assertEquals(
            "ENC",
            NetworkParsers.encryptionLabel(WifiEncryption(true, emptyList(), emptyList(), emptyList()), "NO-ENC", "ENC"),
        )
    }

    @Test
    fun `紧凑计数与码率格式——对齐旧 formatPacketCount（无 M 档）`() {
        assertEquals("999", Format.compactCount(999))
        assertEquals("1k", Format.compactCount(1000))
        assertEquals("1.2k", Format.compactCount(1234))
        assertEquals("10k", Format.compactCount(9999))
        assertEquals("123k", Format.compactCount(123_456)) // k≥100 取整
        assertEquals("1000k", Format.compactCount(999_999))
        assertEquals("1500k", Format.compactCount(1_500_000))
        assertEquals("866.7 Mbit/s", Format.bitrate(866700.0))
    }

    @Test
    fun `网关——默认路由 nexthop 提取`() {
        val dump = JSONObject(
            """
            {"interface":[{"interface":"wan","up":true,"proto":"dhcp","l3_device":"eth0.2",
              "route":[{"target":"0.0.0.0","mask":0,"nexthop":"192.168.1.254"},
                       {"target":"192.168.9.0","mask":24,"nexthop":"10.0.0.1"}]}]}
            """.trimIndent(),
        )
        val ifaces = NetworkParsers.ifaceList(dump, JSONObject("""{}"""))
        assertEquals(1, ifaces.size)
        assertEquals("192.168.1.254", ifaces[0].gateway)
        assertTrue(ifaces[0].dns.isEmpty())
        assertNull(ifaces[0].ipv4)
    }
}
