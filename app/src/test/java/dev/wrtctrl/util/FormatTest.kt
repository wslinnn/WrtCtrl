package dev.wrtctrl.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatTest {

    @Test
    fun `bytes 分级与精度`() {
        assertEquals("0 B", Format.bytes(0))
        assertEquals("512 B", Format.bytes(512))
        assertEquals("1.0 KB", Format.bytes(1024))
        assertEquals("1.5 KB", Format.bytes(1536))
        assertEquals("1.00 MB", Format.bytes(1024 * 1024))
        assertEquals("1.00 GB", Format.bytes(1024L * 1024 * 1024))
    }

    @Test
    fun `rate 非正数回落零`() {
        assertEquals("0 B/s", Format.rate(0))
        assertEquals("0 B/s", Format.rate(-5))
    }

    @Test
    fun `rate 单位与精度`() {
        assertEquals("512 B/s", Format.rate(512))
        assertEquals("2 KB/s", Format.rate(2048))
        assertEquals("1.5 MB/s", Format.rate((1.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun `duration 分级拼接`() {
        assertEquals("0s", Format.duration(0))
        assertEquals("59s", Format.duration(59))
        assertEquals("1m 0s", Format.duration(60))
        assertEquals("1h 1m 1s", Format.duration(3661))
        assertEquals("1d 1h 1m 1s", Format.duration(90061))
    }

    @Test
    fun `bandwidthRates 差分与时间戳`() {
        val series = Format.bandwidthRates(
            org.json.JSONArray("[[10,100,0,200],[13,400,0,800]]"),
        )
        assertEquals(listOf(13L), series.timestamps)
        assertEquals(listOf(100.0), series.rx)
        assertEquals(listOf(200.0), series.tx)
    }

    @Test
    fun `bandwidthRates 计数器回绕钳零`() {
        val series = Format.bandwidthRates(
            org.json.JSONArray("[[10,1000,0,1000],[11,500,0,2000]]"),
        )
        assertEquals(listOf(0.0), series.rx)
        assertEquals(listOf(1000.0), series.tx)
    }

    @Test
    fun `bandwidthRates 非递增时间戳与短样本跳过`() {
        assertTrue(Format.bandwidthRates(org.json.JSONArray("[[10,1,0,1]]")).timestamps.isEmpty())
        assertTrue(Format.bandwidthRates(org.json.JSONArray("[[10,1,0,1],[10,2,0,2]]")).timestamps.isEmpty())
    }
}
