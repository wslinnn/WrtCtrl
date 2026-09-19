package dev.wrtctrl.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class NetBinderTest {

    @Test
    fun `字面量识别——合法 IPv4 与 IPv6，域名与非法形态返回 null`() {
        assertEquals("192.168.2.1", NetBinder.parseIpLiteral("192.168.2.1")?.hostAddress)
        // JVM 的 IPv6 hostAddress 是非压缩形式，断言地址对象而非字符串
        assertEquals(InetAddress.getByName("fd00::1"), NetBinder.parseIpLiteral("fd00::1"))
        assertEquals(InetAddress.getByName("fd00::1"), NetBinder.parseIpLiteral("[fd00::1]"))
        assertNull(NetBinder.parseIpLiteral("router.example.com"))
        assertNull(NetBinder.parseIpLiteral("999.1.1.1"))
        assertNull(NetBinder.parseIpLiteral("192.168.1"))
        assertNull(NetBinder.parseIpLiteral(""))
    }

    @Test
    fun `私网判定——RFC1918、ULA、链路本地、环回为真，公网地址为假`() {
        assertTrue(NetBinder.isPrivate(InetAddress.getByName("192.168.2.1")))
        assertTrue(NetBinder.isPrivate(InetAddress.getByName("10.0.0.1")))
        assertTrue(NetBinder.isPrivate(InetAddress.getByName("172.31.255.1")))
        assertTrue(NetBinder.isPrivate(InetAddress.getByName("fd00::1")))
        assertTrue(NetBinder.isPrivate(InetAddress.getByName("fe80::1")))
        assertTrue(NetBinder.isPrivate(InetAddress.getByName("127.0.0.1")))
        assertFalse(NetBinder.isPrivate(InetAddress.getByName("172.32.0.1")))
        assertFalse(NetBinder.isPrivate(InetAddress.getByName("8.8.8.8")))
        assertFalse(NetBinder.isPrivate(InetAddress.getByName("240e:8800::1")))
        assertFalse(NetBinder.isPrivate(InetAddress.getByName("2001:db8::1")))
    }

    @Test
    fun `前缀匹配——整字节边界与比特边界`() {
        val phone = InetAddress.getByName("192.168.2.137").address
        val router = InetAddress.getByName("192.168.2.1").address
        assertTrue(NetBinder.samePrefix(phone, router, 24))
        // /25 起分属两个子网：.137（0x89）与 .1（0x01）在第 25 比特分叉
        assertFalse(NetBinder.samePrefix(phone, router, 25))
        assertTrue(NetBinder.samePrefix(phone, router, 0))

        val neighbor = InetAddress.getByName("192.168.2.136").address
        assertTrue(NetBinder.samePrefix(phone, neighbor, 31))
        assertFalse(NetBinder.samePrefix(phone, neighbor, 32))

        val v6a = InetAddress.getByName("fd00:1234:5678::1").address
        val v6b = InetAddress.getByName("fd00:1234:5679::1").address
        assertTrue(NetBinder.samePrefix(v6a, v6b, 47))
        assertFalse(NetBinder.samePrefix(v6a, v6b, 48))
    }
}
