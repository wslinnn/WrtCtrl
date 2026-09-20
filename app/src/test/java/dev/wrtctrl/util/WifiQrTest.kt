package dev.wrtctrl.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiQrTest {

    @Test
    fun `escape 五字符转义且反斜杠先行`() {
        // 规范仅要求 \ ; , : " 五字符转义，/ 不转义
        assertEquals("""a\\b/c\;d\:e\"f""", WifiQr.escape("a\\b/c;d:e\"f"))
    }

    @Test
    fun `payload 类型分档与 NOPASS 无 P 段`() {
        // 规范串尾双分号（P 段结束符 + 串结束符）
        assertEquals(
            "WIFI:T:WPA;S:Home;P:pass1;;",
            WifiQr.payload("psk2", "Home", "pass1"),
        )
        assertEquals(
            "WIFI:T:SAE;S:Home;P:pass1;;",
            WifiQr.payload("sae-mixed", "Home", "pass1"),
        )
        assertEquals(
            "WIFI:T:NOPASS;S:Guest;;",
            WifiQr.payload("none", "Guest", null),
        )
        // psk2 但 key 空：不落空 P 段
        assertEquals("WIFI:T:WPA;S:Home;;", WifiQr.payload("psk2", "Home", ""))
    }

    @Test
    fun `payload 特殊字符转义进 ssid 与 key`() {
        assertEquals(
            """WIFI:T:WPA;S:my\;net;P:p\;\:1;;""",
            WifiQr.payload("psk", "my;net", "p;:1"),
        )
    }

    @Test
    fun `isPersonal 企业网不适用`() {
        assertTrue(WifiQr.isPersonal(null))
        assertTrue(WifiQr.isPersonal("none"))
        assertTrue(WifiQr.isPersonal("psk2"))
        assertTrue(WifiQr.isPersonal("psk-mixed"))
        assertTrue(WifiQr.isPersonal("sae"))
        assertFalse(WifiQr.isPersonal("wpa2"))
        assertFalse(WifiQr.isPersonal("wpa3"))
    }

    @Test
    fun `qrModules 生成方阵且空内容返回 null`() {
        val modules = WifiQr.qrModules("WIFI:T:WPA;S:Home;P:pass1;")
        assertNotNull(modules)
        val n = modules!!.size
        assertTrue("模块数应为正方形方阵", modules.all { it.size == n })
        assertTrue(n > 20)
        // 方阵中深浅两色都存在
        assertTrue(modules.any { row -> row.any { it } })
        assertTrue(modules.any { row -> row.any { !it } })
        assertNull(WifiQr.qrModules(""))
    }
}
