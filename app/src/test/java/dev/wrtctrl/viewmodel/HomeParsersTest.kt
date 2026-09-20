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
    fun `memoryPercent 与明细`() {
        val info = JSONObject("""{"memory": {"total": 2147483648, "available": 1288490188}}""")
        assertEquals(40, HomeParsers.memoryPercent(info))
        assertEquals("819 MB / 2 GB", HomeParsers.memoryDetail(info))
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
    fun `wan 地址与掩码`() {
        assertEquals("1.2.3.4/24", HomeParsers.wanIp(dump))
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
        assertEquals("128 MB / 256 MB", list[0].detail)
    }

    @Test
    fun `connections 计数文件缺失或非法返回 null`() {
        assertEquals(123 to 456, HomeParsers.connections("123\n", "456"))
        assertNull(HomeParsers.connections(null, "456"))
        assertNull(HomeParsers.connections("123", "abc"))
    }

    // ── 固件短版本号 / 接口 chip ──

    @Test
    fun `releaseVersion 取短版本号缺失返回 null`() {
        val board = JSONObject("""{"release": {"version": " 23.05.5 "}}""")
        assertEquals("23.05.5", HomeParsers.releaseVersion(board))
        assertNull(HomeParsers.releaseVersion(JSONObject("""{"release": {}}""")))
        assertNull(HomeParsers.releaseVersion(JSONObject("{}")))
    }

    @Test
    fun `ifaceChips 保序剔除loopback并读up标志`() {
        val chips = HomeParsers.ifaceChips(
            JSONObject(
                """
                {"interface": [
                    {"interface": "loopback", "up": true},
                    {"interface": "wan", "up": true},
                    {"interface": "wanb", "up": false}
                ]}
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("wan" to true, "wanb" to false), chips.map { it.name to it.up })
        assertEquals(0, HomeParsers.ifaceChips(JSONObject("""{"interface": []}""")).size)
    }

    // ── contiguousBandwidthTail（停轮询恢复后假连续断档截断） ──

    @Test
    fun `带宽序列连续样本原样保留`() {
        val ts = listOf(1_000L, 1_003L, 1_006L, 1_009L)
        val (rx, tx, out) = contiguousBandwidthTail(
            listOf(1.0, 2.0, 3.0, 4.0),
            listOf(0.5, 0.6, 0.7, 0.8),
            ts,
            11,
        )
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0), rx)
        assertEquals(listOf(0.5, 0.6, 0.7, 0.8), tx)
        assertEquals(ts, out)
    }

    @Test
    fun `带宽序列截掉最后一次断档前的旧样本`() {
        // 模拟停轮询恢复：1006s 后设备缓冲冻结约 35 分钟无人采样，再从 3126s 继续
        val ts = listOf(1_000L, 1_003L, 1_006L, 3_126L, 3_129L)
        val (rx, tx, out) = contiguousBandwidthTail(
            listOf(9.0, 9.0, 9.0, 1.0, 2.0),
            listOf(1.0, 1.0, 1.0, 0.5, 0.6),
            ts,
            11,
        )
        assertEquals(listOf(1.0, 2.0), rx)
        assertEquals(listOf(0.5, 0.6), tx)
        assertEquals(listOf(3_126L, 3_129L), out)
    }

    @Test
    fun `带宽序列断档后只剩单点不越界`() {
        val (rx, _, out) = contiguousBandwidthTail(listOf(1.0, 2.0), listOf(1.0, 1.0), listOf(1_000L, 2_000L), 11)
        assertEquals(listOf(2.0), rx)
        assertEquals(listOf(2_000L), out)
    }
}
