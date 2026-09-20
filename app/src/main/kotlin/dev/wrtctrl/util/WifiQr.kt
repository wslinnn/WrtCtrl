package dev.wrtctrl.util

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * WiFi 二维码：WIFI 串组装 + zxing 生成模块矩阵。纯函数 JVM 可测；
 * 渲染走 Compose Canvas 自绘（ui/component/QrCode.kt），本对象无 Android 依赖。
 */

object WifiQr {

    /**
     * WIFI 串（WiFi 联盟扫码规范）：`WIFI:T:<type>;S:<ssid>;P:<key>;;`。
     * type：none→NOPASS（无 P 段）；sae*→SAE；其余个人网→WPA。
     * 转义：`\` `;` `,` `:` `"` 五字符前加 `\`（规范要求，先转反斜杠）。
     */
    fun payload(encryption: String?, ssid: String, key: String?): String {
        val type = when {
            encryption == null || encryption == "none" -> "NOPASS"
            encryption.startsWith("sae") -> "SAE"
            else -> "WPA"
        }
        return buildString {
            append("WIFI:T:").append(type).append(";S:").append(escape(ssid)).append(';')
            if (type != "NOPASS" && !key.isNullOrBlank()) append("P:").append(escape(key)).append(';')
            append(';')
        }
    }

    /** WIFI 串规范转义：`\` `;` `,` `:` `"` 前加反斜杠 */
    fun escape(s: String): String =
        s.replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace(":", "\\:")
            .replace("\"", "\\\"")

    /**
     * uci wireless 的 encryption 值 → 是否个人网（密码/QR 动作适用）。
     * none 与 psk 前缀、sae 前缀适用；裸 wpa/wpa2/wpa3（802.1x 企业网）不适用。
     */
    fun isPersonal(encryption: String?): Boolean = when {
        encryption == null -> true
        encryption == "none" -> true
        encryption.startsWith("psk") -> true
        encryption.startsWith("sae") -> true
        else -> false
    }

    /**
     * 内容 → 方块矩阵（true = 深色模块）。MARGIN=2 留静区，外围白边再由容器 padding 承担；
     * 编码失败（空内容等）返回 null（空态守卫，调用方不显码）。
     */
    fun qrModules(content: String): Array<BooleanArray>? = try {
        val hints = mapOf(
            EncodeHintType.MARGIN to 2,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
        Array(matrix.height) { y ->
            BooleanArray(matrix.width) { x -> matrix.get(x, y) }
        }
    } catch (e: Exception) {
        null
    }
}
