package dev.wrtctrl.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** OUI 库契约：三档前缀长度、显式 null 短路、随机 MAC/畸形输入拒绝 */
class OuiDbTest {

    private companion object {
        // vendors[0]=apple, vendors[1]=samsung；prefixes 覆盖 9/7/6 三档 + 显式 null 短路
        val DB = OuiDb.fromJson(
            """
            {"vendors":[["apple","Apple"],["samsung","Samsung"]],
             "prefixes":{
               "A483E72C9": 0,
               "B827EB5": 1,
               "C47C5D": 0,
               "F01898": null
             }}
            """.trimIndent(),
        )
    }

    @Test
    fun `nine char prefix longest match wins`() {
        assertEquals("apple", DB.vendorOf("A4:83:E7:2C:9B:04")?.slug)
        assertEquals("Apple", DB.vendorOf("A483E72C9B04")?.name)
    }

    @Test
    fun `seven and six char prefixes resolve`() {
        assertEquals("samsung", DB.vendorOf("B8:27:EB:5A:D1:50")?.slug)
        assertEquals("apple", DB.vendorOf("c47c5d112233")?.slug)
    }

    @Test
    fun `explicit null prefix short circuits shorter lookup`() {
        // F01898 显式 null：即使去掉一位后可能命中 6 位键，也直接判未命中
        assertNull(DB.vendorOf("F0:18:98:11:22:33"))
    }

    @Test
    fun `unknown mac returns null`() {
        assertNull(DB.vendorOf("00:11:22:33:44:55"))
    }

    @Test
    fun `randomized mac is rejected before lookup`() {
        // 首字节低位置位 = 本地管理地址（随机 MAC）：A2、B6、DA…
        assertNull(DB.vendorOf("A2:83:E7:2C:9B:04"))
        assertNull(DB.vendorOf("DA:83:E7:2C:9B:04"))
    }

    @Test
    fun `malformed input returns null`() {
        assertNull(DB.vendorOf(""))
        assertNull(DB.vendorOf("A4:83:E7"))
        assertNull(DB.vendorOf("ZZ:83:E7:2C:9B:04"))
        assertNull(DB.vendorOf("A483E72C9B0G"))
    }

    @Test
    fun `normalize strips separators and uppercases`() {
        assertEquals("A483E72C9B04", DB.normalize("a4-83-e7-2c-9b-04"))
        assertNull(DB.normalize("  "))
    }

    @Test
    fun `invalid json degrades to empty db`() {
        val empty = OuiDb.fromJson("not json")
        assertNull(empty.vendorOf("A4:83:E7:2C:9B:04"))
    }
}
