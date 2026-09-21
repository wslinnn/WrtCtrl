package dev.wrtctrl.viewmodel

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClientParsersTest {

    @Test
    fun `无线接口清单——根对象键控、band 大写、无 band 为 null`() {
        val payload = JSONObject(
            """
            {"radio0":{"config":{"band":"5g"},"interfaces":[{"ifname":"phy0-ap0"}]},
             "radio1":{"config":{},"interfaces":[{"ifname":"phy1-ap0"},{"ifname":""}]}}
            """.trimIndent(),
        )
        val ifaces = ClientParsers.wifiIfaces(payload)
        assertEquals(listOf("phy0-ap0" to "5G", "phy1-ap0" to null), ifaces)
    }

    @Test
    fun `关联终端解析——字段与 hostname 和 IP 合并（mac 大写匹配）`() {
        val results = JSONArray(
            """
            [{"mac":"AA:BB:CC:DD:EE:FF","signal":-48,"connected_time":3661,
              "rx":{"rate":866700,"mhz":80,"vht":true,"mcs":9},
              "tx":{"rate":72200,"mhz":80,"ht":true,"vht":true,"mcs":7}},
             {"signal":-60}]
            """.trimIndent(),
        )
        val clients = ClientParsers.clientsOf(
            "phy0-ap0",
            "5G",
            results,
            mapOf("AA:BB:CC:DD:EE:FF" to "pixel"),
            mapOf("AA:BB:CC:DD:EE:FF" to "192.168.2.50"),
        )
        assertEquals(1, clients.size) // 无 mac 的脏数据剔除
        val c = clients[0]
        assertEquals("AA:BB:CC:DD:EE:FF", c.mac)
        assertEquals(-48, c.signal)
        assertEquals(3661L, c.connectedTime)
        assertEquals("pixel", c.hostname)
        assertEquals("192.168.2.50", c.ip)
        assertEquals("phy0-ap0", c.ifname)
        assertEquals("5G", c.band)
    }

    @Test
    fun `DHCP 双栈解析——v4 与 v6 字段拆分、macaddr 可选、duid 仅 v6`() {
        val payload = JSONObject(
            """
            {"dhcp_leases":[{"hostname":"nas","ipaddr":"192.168.2.10","macaddr":"11:22:33:44:55:66","expires":43200}],
             "dhcp6_leases":[{"hostname":"phone","ip6addr":"2409:8154::123","duid":"000100011e4a2b3c","expires":3600},
                              {"ip6addr":"2409:8154::456","duid":"00010001abcd","expires":0}]}
            """.trimIndent(),
        )
        val (v4, v6) = ClientParsers.dhcpLeases(payload)
        assertEquals(1, v4.size)
        assertEquals("192.168.2.10", v4[0].ip)
        assertEquals("11:22:33:44:55:66", v4[0].macaddr)
        assertEquals(43200L, v4[0].expires)
        assertNull(v4[0].duid)
        assertEquals(2, v6.size)
        assertEquals("phone", v6[0].hostname)
        assertEquals("2409:8154::123", v6[0].ip)
        assertEquals("000100011e4a2b3c", v6[0].duid)
        assertNull(v6[1].macaddr) // DUID-only 客户端
    }

    @Test
    fun `hostname 合并——v6 覆盖 v4（同 mac）`() {
        val v4 = listOf(DhcpLease("old-name", "AA:BB:CC:DD:EE:FF", "192.168.2.10", null, 100))
        val v6 = listOf(DhcpLease("new-name", "aa:bb:cc:dd:ee:ff", "2409::1", "duid", 100))
        val map = ClientParsers.hostnameMap(v4, v6)
        assertEquals("new-name", map["AA:BB:CC:DD:EE:FF"])
    }

    @Test
    fun `ipMap 与静态租约 mac 集合`() {
        val v4 = listOf(
            DhcpLease("nas", "aa:bb:cc:dd:ee:ff", "192.168.2.10", null, 100),
            DhcpLease("noip", "11:22:33:44:55:66", "", null, 100),
        )
        assertEquals("192.168.2.10", ClientParsers.ipMap(v4)["AA:BB:CC:DD:EE:FF"])
        assertEquals(1, ClientParsers.ipMap(v4).size)
        val uci = JSONObject(
            """
            {"@host[0]":{"name":"@host[0]","section_type":"host","anonymous":false,
              "options":{"name":"printer","mac":"AA:BB:CC:00:00:01","ip":"192.168.2.50"}},
             "@host[1]":{"name":"@host[1]","section_type":"host","anonymous":false,
              "options":{"name":"nolower","mac":"","ip":"192.168.2.51"}},
             "defaults":{"name":"defaults","section_type":"defaults","anonymous":false,
              "options":{"dhcp_option":"42"}}}
            """.trimIndent(),
        )
        assertEquals(
            listOf(StaticHost("@host[0]", "AA:BB:CC:00:00:01", "printer", "192.168.2.50")),
            ClientParsers.staticHosts(uci),
        )
    }

    @Test
    fun `拉黑规则解析——block_ 前缀命中、非前缀忽略、mac 小写归一`() {
        val uci = JSONObject(
            """
            {"cfg044a3c":{"name":"cfg044a3c","section_type":"rule","anonymous":true,
              "options":{"name":"block_aa:bb:cc:dd:ee:ff","src":"lan","dest":"wan",
                         "src_mac":"AA:BB:CC:DD:EE:FF","proto":"all","target":"REJECT"}},
             "cfg055b4d":{"name":"cfg055b4d","section_type":"rule","anonymous":true,
              "options":{"name":"allow_guest","src":"lan","dest":"wan","src_mac":"11:22:33:44:55:66"}},
             "cfg066c5e":{"name":"cfg066c5e","section_type":"rule","anonymous":true,
              "options":{"name":"block_00:11:22:33:44:55","src":"wan","dest":"lan"}},
             "defaults":{"name":"defaults","section_type":"defaults","anonymous":false,
              "options":{"input":"ACCEPT"}}}
            """.trimIndent(),
        )
        val blocked = ClientParsers.blockedMacs(uci)
        assertEquals(mapOf("aa:bb:cc:dd:ee:ff" to "cfg044a3c"), blocked)
    }

    @Test
    fun `拉黑规则解析——src_mac 为 uci list 数组形态时逐个收录`() {
        val uci = JSONObject(
            """
            {"cfg077d6f":{"name":"cfg077d6f","section_type":"rule","anonymous":true,
              "options":{"name":"block_list","src":"lan","dest":"wan",
                         "src_mac":["AA:BB:CC:00:00:01","AA:BB:CC:00:00:02"],"target":"DROP"}}}
            """.trimIndent(),
        )
        assertEquals(
            mapOf("aa:bb:cc:00:00:01" to "cfg077d6f", "aa:bb:cc:00:00:02" to "cfg077d6f"),
            ClientParsers.blockedMacs(uci),
        )
    }

    @Test
    fun `速率串——与 LuCI 速率格式一致 五形态`() {
        assertEquals(
            "866.7 Mbit/s 80MHz vht-mcs9",
            ClientParsers.rateText(RateInfo(rate = 866700.0, mhz = 80, ht = false, vht = true, he = false, eht = false, mcs = 9)),
        )
        // ht 与 vht 同时为真取 ht（ht 优先）
        assertEquals(
            "72.2 Mbit/s 80MHz ht-mcs7",
            ClientParsers.rateText(RateInfo(rate = 72200.0, mhz = 80, ht = true, vht = true, he = false, eht = false, mcs = 7)),
        )
        // 无 mcs 数字则不带 mcs 段（类型标记只在 mcs 段内出现）
        assertEquals(
            "72.2 Mbit/s 80MHz",
            ClientParsers.rateText(RateInfo(rate = 72200.0, mhz = 80, ht = false, vht = true, he = false, eht = false, mcs = null)),
        )
        // he
        assertEquals(
            "1201.0 Mbit/s 80MHz he-mcs11",
            ClientParsers.rateText(RateInfo(rate = 1201000.0, mhz = 80, ht = false, vht = false, he = true, eht = false, mcs = 11)),
        )
        assertEquals("- Mbit/s", ClientParsers.rateText(null))
        assertEquals("- Mbit/s 80MHz", ClientParsers.rateText(RateInfo(null, 80, false, false, false, false, null)))
    }
}
