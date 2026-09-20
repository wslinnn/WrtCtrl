package dev.wrtctrl.viewmodel

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatisticsParsersTest {

    @Test
    fun `接口清单——br-lan 置顶、滤 lo 与显式 down、余按名排`() {
        val devices = JSONObject(
            """
            {"eth1":{"name":"eth1","up":true},
             "lo":{"name":"lo","up":true},
             "br-lan":{"name":"br-lan","up":true},
             "wan":{"name":"wan","up":false},
             "eth0":{"name":"eth0","up":true},
             "phy0-ap0":{"name":"phy0-ap0","up":true}}
            """.trimIndent(),
        )
        assertEquals(
            listOf("br-lan", "eth0", "eth1", "phy0-ap0"),
            StatisticsParsers.interfaceOptions(devices),
        )
    }

    @Test
    fun `负载数行——定点 ÷100、缺列行跳过`() {
        val rows = StatisticsParsers.loadRows(
            JSONArray("[[1789800000,40,50,60],[1789800003,80,90,100],[1789800006,120]]"),
        )
        assertEquals(2, rows.size)
        assertEquals(LoadRow(1789800000, 0.4, 0.5, 0.6), rows[0])
        assertEquals(LoadRow(1789800003, 0.8, 0.9, 1.0), rows[1])
    }

    @Test
    fun `窗口统计——当前=末值、均值、峰值；空列表 null`() {
        val stats = StatisticsParsers.windowStats(listOf(1.0, 3.0, 2.0))
        assertEquals(2.0, stats!!.current, 0.0)
        assertEquals(2.0, stats.average, 1e-9)
        assertEquals(3.0, stats.peak, 0.0)
        assertNull(StatisticsParsers.windowStats(emptyList()))
    }

    @Test
    fun `负载值格式——两位小数固定点`() {
        assertEquals("0.40", StatisticsParsers.loadText(0.4))
        assertEquals("1.05", StatisticsParsers.loadText(1.049))
    }

    @Test
    fun `本窗口传输——梯形积分与守卫`() {
        // 10 B/s 恒定 3s → 30 B；末段 Δt=0 跳过
        assertEquals(
            30L,
            StatisticsParsers.windowTransfer(
                listOf(10.0, 10.0, 10.0),
                listOf(100L, 103L, 103L),
            ),
        )
        // 线性 0→10 B/s over 10s → 50 B
        assertEquals(
            50L,
            StatisticsParsers.windowTransfer(
                listOf(0.0, 10.0),
                listOf(0L, 10L),
            ),
        )
        assertEquals(0L, StatisticsParsers.windowTransfer(listOf(1.0), listOf(1L)))
        assertEquals(0L, StatisticsParsers.windowTransfer(emptyList(), emptyList()))
    }
}
