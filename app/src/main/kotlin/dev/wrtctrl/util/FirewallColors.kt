package dev.wrtctrl.util

/**
 * 防火墙 zone 颜色哈希。
 * 确定性：同一 zone 名恒得同一色；lan→#90f090、wan→#f09090（单测锚定）。
 * 位语义注释：JS 全程 32 位截断（ToUint32/ToInt32），Kotlin Int 足迹一致——
 * 唯一差异是 _s8 的「无符号化」（JS (n-256)>>>0），等价于 mod 2^32 加法，故按有符号直加。
 */
object FirewallColors {

    private const val NEUTRAL = "#bbbbbb"
    private const val LAN = "#90f090"
    private const val WAN = "#f09090"

    /** zone 名 → hex 颜色。空/`*` 中性灰；lan/wan 固定；其余哈希派生 */
    fun zoneColor(name: String?): String {
        if (name.isNullOrEmpty() || name == "*") return NEUTRAL
        if (name == "lan") return LAN
        if (name == "wan") return WAN
        return deriveColor(name)
    }

    private fun u16(bytes: ByteArray, off: Int): Int =
        ((bytes[off + 1].toInt() and 0xFF) shl 8) or (bytes[off].toInt() and 0xFF)

    private fun s8(bytes: ByteArray, off: Int): Int =
        bytes[off].toInt().let { if (it > 0x7F) it - 256 else it }

    /** SuperFastHash → 8 位 hex（JS 版逐分支一致；charCodeAt>0xFFFF 分支不可达，UTF-8 编码等价） */
    private fun superFastHash(s: String): String? {
        val bytes = s.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty()) return null
        var hash = bytes.size
        var len = bytes.size ushr 2
        var off = 0
        while (len-- > 0) {
            hash += u16(bytes, off)
            val tmp = (u16(bytes, off + 2) shl 11) xor hash
            hash = (hash shl 16) xor tmp
            hash += hash ushr 11
            off += 4
        }
        when (bytes.size and 3) {
            3 -> {
                hash += u16(bytes, off)
                hash = hash xor (hash shl 16)
                hash = hash xor (s8(bytes, off + 2) shl 18)
                hash += hash ushr 11
            }
            2 -> {
                hash += u16(bytes, off)
                hash = hash xor (hash shl 11)
                hash += hash ushr 17
            }
            1 -> {
                hash += s8(bytes, off)
                hash = hash xor (hash shl 10)
                hash += hash ushr 1
            }
        }
        hash = hash xor (hash shl 3)
        hash += hash ushr 5
        hash = hash xor (hash shl 4)
        hash += hash ushr 17
        hash = hash xor (hash shl 25)
        hash += hash ushr 6
        // %08x 负数即补码八位十六进制（JS toString(16)>>>0 同语义），Locale.US 固定
        return String.format(java.util.Locale.US, "%08x", hash)
    }

    // ── 64-bit PRNG（4 个 16-bit 字，小端）──

    private var prngS = IntArray(4)

    private fun seed(n: Int) {
        val v = n - 1
        prngS[0] = v and 0xFFFF
        prngS[1] = (v ushr 16) and 0xFFFF
        prngS[2] = 0
        prngS[3] = 0
    }

    /** 16-bit 肢乘法（Long 承载乘积——(2^16-1)^2 超 Int 位宽，JS double 精确值） */
    private fun mul(a: IntArray, b: IntArray): IntArray {
        val r = LongArray(8)
        for (j in 0..3) {
            var k = 0L
            for (i in 0..3) {
                val t = a[i].toLong() * b[j].toLong() + r[i + j] + k
                r[i + j] = t and 0xFFFF
                k = t ushr 16
            }
            r[j + 4] = k
        }
        return intArrayOf(r[0].toInt(), r[1].toInt(), r[2].toInt(), r[3].toInt())
    }

    private fun add(a: IntArray, n: Int): IntArray {
        val r = IntArray(4)
        var k = n
        for (i in 0..3) {
            val t = a[i] + k
            r[i] = t and 0xFFFF
            k = t shr 16
        }
        return r
    }

    private fun shr(a: IntArray, n0: Int): IntArray {
        var n = n0
        val r = intArrayOf(a[0], a[1], a[2], a[3])
        var i = 4
        var k = 0
        while (n > 16) {
            n -= 16
            i--
            for (j in 0..2) r[j] = r[j + 1]
            r[3] = 0
        }
        while (i > 0) {
            val s = r[i - 1]
            r[i - 1] = (s ushr n) or k
            k = (s and ((1 shl n) - 1)) shl (16 - n)
            i--
        }
        return r
    }

    private fun int(): Int {
        prngS = mul(prngS, intArrayOf(0x7F2D, 0x4C95, 0xF42D, 0x5851))
        prngS = add(prngS, 1)
        val r = shr(prngS, 33)
        return (r[1] shl 16) or r[0]
    }

    private fun get(): Double = (int() % 0x7FFFFFFF).toDouble() / 0x7FFFFFFF.toDouble()

    /** JS _get(bound) 单参形态：l=1、u=bound → floor(r*bound)+1，区间 1..bound（差一易错点） */
    private fun get(bound: Int): Int = Math.floor(get() * bound).toInt() + 1

    private fun deriveColor(string: String): String {
        // JS parseInt(...,16) 可达 2^32-1（高位置位），_seed 内 (n-1)|0 才截回 32 位——Long 承载后 toInt
        seed(superFastHash(string)!!.toLong(16).toInt())
        val r = get(128)
        val g = get(128)
        var min = 0
        var max = 128
        if (r + g < 128) min = 128 - r - g else max = 255 - r - g
        val b = min + Math.floor(get() * (max - min)).toInt()
        fun hex(v: Int): String {
            val x = Integer.toHexString(0xFF - v)
            return if (x.length < 2) "0$x" else x
        }
        return "#" + hex(r) + hex(g) + hex(b)
    }
}
