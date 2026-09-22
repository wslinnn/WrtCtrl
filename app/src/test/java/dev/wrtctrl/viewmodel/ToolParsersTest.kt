package dev.wrtctrl.viewmodel

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工具页纯解析（route/process/startup/conntrack/stats/rrdns/log），与 LuCI 语义一致 */
class ToolParsersTest {

    // ── route ──

    @Test
    fun route_line_full_tokens() {
        val r = ToolParsers.parseRouteLine(
            "10.0.0.0/16 via 192.168.2.254 dev br-lan src 192.168.2.1 table 100 scope link",
            "ipv4",
        )!!
        assertEquals("10.0.0.0/16", r.destination)
        assertEquals("192.168.2.254", r.gateway)
        assertEquals("br-lan", r.device)
        assertEquals("192.168.2.1", r.src)
        assertEquals("link", r.scope)
        assertEquals("100", r.table)
        assertEquals("ipv4", r.family)
    }

    @Test
    fun route_line_default_and_types() {
        val def = ToolParsers.parseRouteLine("default via 192.168.2.1 dev wan", "ipv4")!!
        assertEquals("default", def.destination)
        assertEquals("route_type_default", resName(def.typeRes))
        val net = ToolParsers.parseRouteLine("192.168.1.0/24 dev br-lan scope link", "ipv4")!!
        assertEquals("route_type_network", resName(net.typeRes))
        val host = ToolParsers.parseRouteLine("192.168.2.55 dev wan scope link src 192.168.2.10", "ipv4")!!
        assertEquals("route_type_host", resName(host.typeRes))
    }

    // local/broadcast 行的实际目的地是地址（local 是被跳过的 keyword），类型映射不实现死分支
    @Test
    fun route_local_row_maps_to_host_not_dead_branch() {
        val local = ToolParsers.parseRouteLine(
            "local 192.168.2.1 dev br-lan scope host src 192.168.2.1",
            "ipv4",
        )!!
        assertEquals("192.168.2.1", local.destination)
        assertEquals("route_type_host", resName(local.typeRes))
    }

    @Test
    fun route_family_merge_and_blank() {
        val rows = ToolParsers.parseRoutes("default via 1.1.1.1 dev wan\n", "fd00::/64 dev lan\n")
        assertEquals(2, rows.size)
        assertTrue(ToolParsers.parseRoutes(null, "  ").isEmpty())
    }

    // ── process ──

    @Test
    fun process_parse_sort_cpu_desc_and_fields() {
        val arr = JSONArray(
            """[
            {"PID":"101","USER":"root","PPID":"1","%CPU":"0.5","%MEM":"1.2","VSZ":"12.5m","STAT":"S","COMMAND":"/usr/sbin/dnsmasq -C"},
            {"PID":"7","USER":"root","PPID":"0","%CPU":"2.0","%MEM":"0.1","VSZ":"1024","STAT":"R","COMMAND":"[kworker/0:1]"},
            {"PID":"55","USER":"nobody","PPID":"101","%CPU":"","%MEM":"0.3","VSZ":"2048","STAT":"Z","COMMAND":"sleep"}
            ]""",
        )
        val rows = ToolParsers.parseProcesses(arr)
        assertEquals(listOf("7", "101", "55"), rows.map { it.pid }) // 2.0 > 0.5 > 空(0)
        assertEquals("[kworker/0:1]", rows[0].name) // 内核线程原样
        assertEquals("dnsmasq", rows[1].name) // 首 token basename
        assertEquals("sleep", rows[2].name)
        assertEquals("12.5MB(1.2)", rows[1].memoryText) // 12.5m → ×1024 KB → ÷1024 = 12.5 MB
        assertEquals("2.0MB(0.3)", rows[2].memoryText)
    }

    @Test
    fun process_name_blank() {
        assertEquals("Unknown", ToolParsers.processName(""))
        assertEquals("top", ToolParsers.processName("/usr/bin/top -b"))
    }

    // ── startup ──

    @Test
    fun startup_sorted_by_start_then_name() {
        val data = JSONObject(
            """{
            "firewall": {"start": 19, "enabled": true, "running": true},
            "boot": {"start": 10, "enabled": true, "running": false},
            "cron": {"start": 19, "enabled": false, "running": false},
            "disabled": {"start": 5, "enabled": false, "running": false}
            }""",
        )
        val rows = ToolParsers.parseStartup(data)
        // start 相同时按 name 决次（HashMap 键序不可依赖）
        assertEquals(listOf("disabled", "boot", "cron", "firewall"), rows.map { it.name })
        assertEquals("startup_running", resName(rows[3].statusRes))
        assertEquals("startup_stopped", resName(rows[1].statusRes))
        assertEquals("startup_disabled", resName(rows[0].statusRes))
        // 缺 start 键回落 999
        assertEquals(999, ToolParsers.parseStartup(JSONObject("""{"x":{}}"""))[0].start)
    }

    // ── conntrack ──

    @Test
    fun conntrack_filters_loopback_sorts_by_bytes() {
        val arr = JSONArray(
            """[
            {"layer3":"ipv4","layer4":"tcp","src":"127.0.0.1","dst":"127.0.0.1","bytes":100,"packets":2},
            {"layer3":"ipv4","layer4":"udp","src":"192.168.2.10","dst":"8.8.8.8","sport":5353,"bytes":500,"packets":5},
            {"layer3":"ipv6","layer4":"tcp","src":"::1","dst":"::1","bytes":10,"packets":1},
            {"layer3":"ipv4","layer4":"tcp","src":"192.168.2.10","dst":"1.1.1.1","sport":40000,"dport":443,"bytes":9000,"packets":20}
            ]""",
        )
        val (total, rows) = ToolParsers.parseConntrack(arr)
        assertEquals(2, total)
        assertEquals("1.1.1.1", rows[0].dst) // 9000 > 500
        assertEquals("TCP", rows[0].protocol) // 大写
        assertEquals(5353, rows[1].sport)
        assertNull(rows[1].dport)
    }

    @Test
    fun conntrack_stats_windows() {
        val points = JSONArray(
            """[[100, 10, 20, 1], [200, 30, 20, 3], [300, 20, 60, 2]]""",
        )
        val (udp, tcp, other) = ToolParsers.parseConntrackStats(points)!!
        assertEquals(20.0, udp.current, 0.001)
        assertEquals(20.0, udp.average, 0.001)
        assertEquals(30.0, udp.peak, 0.001)
        assertEquals(60.0, tcp.peak, 0.001)
        assertEquals(2.0, other.average, 0.001) // other col=[1,3,2]
        assertNull(ToolParsers.parseConntrackStats(JSONArray("[]")))
    }

    /** 行数上限 1000，字节降序取头部（top talkers），total 为截断前全量 */
    @Test
    fun conntrack_caps_rows_keeps_top_bytes_and_total() {
        val sb = StringBuilder("[")
        for (i in 0 until 1200) {
            if (i > 0) sb.append(",")
            sb.append("""{"layer3":"ipv4","layer4":"tcp","src":"10.0.0.$i","dst":"1.1.1.1","bytes":$i,"packets":1}""")
        }
        sb.append("]")
        val (total, rows) = ToolParsers.parseConntrack(JSONArray(sb.toString()))
        assertEquals(1200, total)
        assertEquals(1000, rows.size)
        assertEquals(1199, rows[0].bytes) // 头部 = 最大字节
        assertEquals(200, rows[rows.size - 1].bytes) // 第 1000 名
    }

    @Test
    fun rrdns_and_log_lines() {
        val map = ToolParsers.parseRrdns(JSONObject("""{"1.1.1.1":"one.one.one.one","8.8.8.8":""}"""))
        assertEquals("one.one.one.one", map["1.1.1.1"])
        assertEquals(1, map.size) // 空主机名不入表
        val lines = ToolParsers.parseLogLines(
            JSONArray("""[{"text":"line1","level":"err"},{"text":"line2","level":"warn"}]"""),
        )
        assertEquals("err", lines[0].level)
        assertEquals("warn", lines[1].level)
    }

    /** 日志行数上限 2000 取尾（「滚底看最新」语义） */
    @Test
    fun log_lines_cap_keeps_tail() {
        val sb = StringBuilder("[")
        for (i in 0 until 2200) {
            if (i > 0) sb.append(",")
            sb.append("""{"text":"line$i","level":"info"}""")
        }
        sb.append("]")
        val lines = ToolParsers.parseLogLines(JSONArray(sb.toString()))
        assertEquals(2000, lines.size)
        assertEquals("line200", lines[0].text) // 最早 200 行（line0..line199）被裁
        assertEquals("line2199", lines[lines.size - 1].text)
    }

    /** R.string 字段 id → 资源名（断言用；R.string 常量在 JVM 单测 classpath 可用） */
    private fun resName(id: Int): String =
        dev.wrtctrl.R.string::class.java.declaredFields.first { it.getInt(null) == id }.name
}
