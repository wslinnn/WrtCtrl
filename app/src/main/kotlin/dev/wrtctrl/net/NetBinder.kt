package dev.wrtctrl.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import java.net.InetAddress

/**
 * LAN 网络选择器（治「WiFi 下浏览器能开 LuCI、app 连接超时」）。
 *
 * Android 只把通过互联网验证的网络设为默认网络，进程内裸 socket（Rust reqwest、
 * ping 二进制）一律跟随默认网络。当 WiFi 无互联网（如 IPv6-only 出口且无 NAT64）
 * 而其他网络在册时，发往 192.168.x.x 的连接会被标记到不覆盖该网段的网络直至超时；
 * 浏览器自带多网络回退不受影响——所以「浏览器正常」不能证明默认网络可达路由器。
 *
 * 策略：目标是私网字面地址时，把进程绑定到覆盖它的非 VPN 网络
 * （bindProcessToNetwork 对进程内所有 socket 生效，含 JNI 层的 Rust socket）；
 * 目标为域名/公网地址时解除绑定回系统默认。绑定是进程级的且随进程消亡，
 * 切换设备时重新评估即可。
 */
object NetBinder {

    /** 当前绑定态（bindProcessToNetwork 不落盘，进程重启自然归零） */
    @Volatile
    private var boundTo: Network? = null

    /** 按目标主机选择/解除进程网络绑定；返回一行网络拓扑描述，供错误卡诊断 */
    fun bindForHost(context: Context, host: String): String {
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: return "no connectivity service"
        val target = parseIpLiteral(host)
        if (target == null || !isPrivate(target)) {
            release(cm)
            return "target=$host via default"
        }
        val infos = cm.allNetworks.map { NetInfo(it, cm.getNetworkCapabilities(it), cm.getLinkProperties(it)) }
        val desc = "nets=${infos.joinToString(" ") { it.describe() }} default=${describeNet(infos, cm.activeNetwork)}"

        // 已绑定的网络仍覆盖目标：保持，避免主页轮询期间反复解绑重绑
        if (infos.firstOrNull { it.net == boundTo }?.covers(target) == true) {
            return "$desc keep"
        }
        release(cm)
        val covering = infos.filter { !it.vpn && it.covers(target) }
        // 默认网络本身覆盖则零介入；否则优先 WiFi，再取任一覆盖网络
        val chosen = covering.firstOrNull { it.net == cm.activeNetwork }
            ?: covering.firstOrNull { it.wifi }
            ?: covering.firstOrNull()
        return if (chosen != null && cm.bindProcessToNetwork(chosen.net)) {
            boundTo = chosen.net
            "$desc bind>${chosen.describe()}"
        } else {
            "$desc no-covering-network"
        }
    }

    private fun release(cm: ConnectivityManager) {
        if (boundTo != null) {
            cm.bindProcessToNetwork(null)
            boundTo = null
        }
    }

    /** 网络快照：能力 + 链路地址，覆盖判定与诊断描述都基于它 */
    private class NetInfo(val net: Network, private val caps: NetworkCapabilities?, private val lp: LinkProperties?) {
        val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        /** 目标是否落在该网络任一链路地址的网段内（LinkAddress 自带前缀，免依赖 RouteInfo） */
        fun covers(target: InetAddress): Boolean {
            val ta = target.address
            return lp?.linkAddresses.orEmpty().any { la ->
                val laa = la.address.address
                laa.size == ta.size && la.prefixLength in 0..laa.size * 8 &&
                    samePrefix(laa, ta, la.prefixLength)
            }
        }

        fun describe(): String {
            val transport = listOf(
                NetworkCapabilities.TRANSPORT_WIFI to "wifi",
                NetworkCapabilities.TRANSPORT_CELLULAR to "cell",
                NetworkCapabilities.TRANSPORT_ETHERNET to "eth",
                NetworkCapabilities.TRANSPORT_VPN to "vpn",
                NetworkCapabilities.TRANSPORT_BLUETOOTH to "bt",
            ).filter { caps?.hasTransport(it.first) == true }
                .joinToString("+") { it.second }
                .ifEmpty { "?" }
            val addrs = lp?.linkAddresses.orEmpty()
                .joinToString(",") { "${it.address.hostAddress}/${it.prefixLength}" }
                .ifEmpty { "-" }
            return "$transport${if (vpn) "*" else ""}[$addrs]"
        }
    }

    private fun describeNet(infos: List<NetInfo>, net: Network?): String =
        net?.let { n -> infos.firstOrNull { it.net == n }?.describe() } ?: "none"

    /** 仅识别 IP 字面量（不触发 DNS）；域名/非法形态返回 null */
    internal fun parseIpLiteral(host: String): InetAddress? {
        val h = host.trim().removePrefix("[").removeSuffix("]")
        val ipv4 = IPV4.matches(h) && h.split('.').all { it.toInt() in 0..255 }
        val ipv6 = IPV6.matches(h) && (h.contains("::") || h.count { it == ':' } == 7)
        if (!ipv4 && !ipv6) return null
        return runCatching { InetAddress.getByName(h) }.getOrNull()
    }

    /** 私网/ULA/链路本地/环回 → 需要 LAN 绑定；公网地址随默认网络 */
    internal fun isPrivate(a: InetAddress): Boolean =
        a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || isUla(a)

    /** ULA fc00::/7 不属于 isSiteLocalAddress（那是已废弃的 fec0::/10），单独判 */
    private fun isUla(a: InetAddress): Boolean =
        a.address.size == 16 && (a.address[0].toInt() and 0xfe) == 0xfc

    /** 前 prefixLength 比特相同（调用方已保证两数组同长度） */
    internal fun samePrefix(a: ByteArray, b: ByteArray, prefixLength: Int): Boolean {
        val full = prefixLength / 8
        for (i in 0 until full) {
            if (a[i] != b[i]) return false
        }
        val rem = prefixLength % 8
        if (rem == 0) return true
        val mask = (0xff shl (8 - rem)) and 0xff
        return (a[full].toInt() and mask) == (b[full].toInt() and mask)
    }

    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    private val IPV6 = Regex("""^[0-9a-fA-F:]+$""")
}
