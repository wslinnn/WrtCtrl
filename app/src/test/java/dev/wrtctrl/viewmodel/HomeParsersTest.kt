package dev.wrtctrl.viewmodel

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeParsersTest {

    // ── cpuPercent（luci getCPUUsage，单路径解析） ──

    @Test
    fun `cpuUsage 归一化小数转百分比`() {
        assertEquals(42, HomeParsers.cpuPercent(JSONObject("""{"cpuusage": 0.42}""")))
        assertEquals(100, HomeParsers.cpuPercent(JSONObject("""{"cpuusage": 1}""")))
    }

    @Test
    fun `cpuUsage 百分数直读并钳制`() {
        assertEquals(42, HomeParsers.cpuPercent(JSONObject("""{"cpuusage": 42}""")))
        assertEquals(100, HomeParsers.cpuPercent(JSONObject("""{"cpuusage": 150}""")))
        assertEquals(0, HomeParsers.cpuPercent(JSONObject("""{"cpuusage": 0}""")))
    }

    @Test
    fun `cpuUsage 字符串取首个数字`() {
        assertEquals(3, HomeParsers.cpuPercent(JSONObject("""{"cpuusage": "CPU: 3%"}""")))
        assertEquals(85, HomeParsers.cpuPercent(JSONObject("""{"cpuusage": "85%"}""")))
    }

    @Test
    fun `cpuUsage 缺失或乱码返回 null`() {
        assertNull(HomeParsers.cpuPercent(JSONObject("{}")))
        assertNull(HomeParsers.cpuPercent(JSONObject("""{"cpuusage": "n/a"}""")))
    }

    // ── tempC（luci getTempInfo，无传感器降级） ──

    @Test
    fun `tempinfo 数字与带单位字符串`() {
        assertEquals(42, HomeParsers.tempC(JSONObject("""{"tempinfo": 42}""")))
        assertEquals(42, HomeParsers.tempC(JSONObject("""{"tempinfo": "41.8 ℃"}""")))
    }

    @Test
    fun `tempinfo 为零或缺失表示无传感器`() {
        assertNull(HomeParsers.tempC(JSONObject("""{"tempinfo": 0}""")))
        assertNull(HomeParsers.tempC(JSONObject("{}")))
    }

    // ── load / memory（system info） ──

    @Test
    fun `load 定点数转三值均值`() {
        val info = JSONObject("""{"load": [65536, 131072, 196608]}""")
        assertEquals("1.00 2.00 3.00", HomeParsers.load(info))
        assertNull(HomeParsers.load(JSONObject("""{"load": [65536]}""")))
        assertNull(HomeParsers.load(JSONObject("{}")))
    }

    @Test
    fun `memoryPercent 与明细`() {
        val info = JSONObject("""{"memory": {"total": 2147483648, "available": 1288490188}}""")
        assertEquals(40, HomeParsers.memoryPercent(info))
        assertEquals("819.20 MB / 2.00 GB", HomeParsers.memoryDetail(info))
        assertEquals(0, HomeParsers.memoryPercent(JSONObject("""{"memory": {"total": 0, "available": 0}}""")))
        assertEquals(0, HomeParsers.memoryPercent(JSONObject("{}")))
    }

    // ── 网络信息（network.interface dump） ──

    private val dump = JSONObject(
        """
        {"interface": [
            {"interface": "lan", "ipv4-address": [{"address": "192.168.1.1", "mask": 24}]},
            {"interface": "wan",
             "ipv4-address": [{"address": "1.2.3.4", "mask": 24}],
             "route": [{"target": "0.0.0.0", "mask": 0, "nexthop": "1.2.3.1"}],
             "dns-server": ["8.8.8.8", "1.1.1.1", "9.9.9.9"]}
        ]}
        """.trimIndent(),
    )

    @Test
    fun `wan lan 地址与掩码`() {
        assertEquals("1.2.3.4/24", HomeParsers.wanIp(dump))
        assertEquals("192.168.1.1/24", HomeParsers.lanIp(dump))
    }

    @Test
    fun `网关取默认路由 nexthop`() {
        assertEquals("1.2.3.1", HomeParsers.gateway(dump))
        assertNull(HomeParsers.gateway(JSONObject("""{"interface": []}""")))
    }

    @Test
    fun `dns 最多取两条逗号连接`() {
        assertEquals("8.8.8.8, 1.1.1.1", HomeParsers.dns(dump))
    }

    // ── 挂载点（luci getMountPoints） ──

    @Test
    fun `mountList 剔除根与dev且overlay优先`() {
        val mounts = JSONObject(
            """
            {"result": [
                {"device": "tmpfs", "mount": "/tmp", "size": 268435456, "free": 134217728},
                {"device": "/dev/root", "mount": "/", "size": 100, "free": 50},
                {"device": "overlay", "mount": "/overlay", "size": 268435456, "free": 134217728},
                {"device": "/dev/sda1", "mount": "/mnt/sda1", "size": 1000, "free": 250}
            ]}
            """.trimIndent(),
        )
        val list = HomeParsers.mountList(mounts)
        assertEquals(listOf("/overlay", "/tmp", "/mnt/sda1"), list.map { it.mount })
        assertEquals(50, list[0].usagePercent)
        assertEquals("128.00 MB / 256.00 MB", list[0].detail)
    }

    @Test
    fun `connectionsText 计数文件缺失返回 null`() {
        assertEquals("123 / 456", HomeParsers.connectionsText("123\n", "456"))
        assertNull(HomeParsers.connectionsText(null, "456"))
    }
}
