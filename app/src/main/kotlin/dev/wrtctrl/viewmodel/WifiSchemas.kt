package dev.wrtctrl.viewmodel

import androidx.annotation.StringRes
import dev.wrtctrl.R
import dev.wrtctrl.viewmodel.plugin.UciCandidates
import dev.wrtctrl.viewmodel.plugin.UciEntry
import dev.wrtctrl.viewmodel.plugin.UciFieldSpec
import dev.wrtctrl.viewmodel.plugin.UciFieldType
import dev.wrtctrl.viewmodel.plugin.UciOption
import org.json.JSONObject

/**
 * wifi 编辑器 schema。
 * 字段集 = 既定字段白名单 ∪ MTK 设备级组（MTK 专属字段，字段表与 luci-app-mtwifi-cfg 对齐）。
 * 分支判定 mtk = uci wireless 的 type ∈ {mtwifi, mtk, mtkwifi}（MTK 三分支检测集；
 * luci-mtk 自身只认 'mtwifi'，padavanonly 固件探测写入的即 'mtwifi'）。
 *
 * 有意偏离/降级：
 * - 带宽 htmode = 静态白名单（上限 HE160，对 mt7981/7986 够用）∪ iwinfo htmodes
 *   候选合并（fieldOptions 静态优先去重）；候选缺失白名单兜底。
 * - txpower 双语义：MTK 固件为百分比 1-100（百分比语义，非 dBm）→ TEXT + 范围校验；
 *   标准固件走 SELECT（auto + iwinfo txpowerlist）。若实测核对 txpowerlist 与 uci 语义不符，
 *   仅需把标准分支同样降级 TEXT。
 * - noscan 仅 band='2g' 渲染（构建期按 band 过滤，不走 depends——band 不是可编辑字段）。
 * - wapp 无条件显示（简化 luci 的 /sbin/startwapp.sh 存在性探测）。
 * - iface mumimo_dl/ul 写后端 dat 硬编码 0 不生效（mtwifi_cfg 硬编码），照迁保 parity + hint 说明。
 */
object WifiSchemas {

    /** htmode 静态白名单（上限 HE160） */
    private val HWMODES =
        listOf("NOHT", "HT20", "HT40", "VHT20", "VHT40", "VHT80", "VHT160", "HE20", "HE40", "HE80", "HE160")

    /** encryption 枚举（与 luci-mtk 分支一致）；key 密码依赖集一致 */
    private val KEYED_ENCRYPTIONS = listOf("psk", "psk2", "psk-mixed", "sae", "sae-mixed")

    private const val IFACE_TYPE = "wifi-iface"

    /** kicklow/assocthres/steeringthresold 运行时 iwpriv 阈值：-100..0 的整数 */
    private const val DBM_PATTERN = "^(?:0|-(?:[1-9][0-9]?|100))$"

    /** radio（wifi-device）编辑字段。band2g 仅控制 noscan 显隐 */
    fun radioSchema(mtk: Boolean, band2g: Boolean): List<UciFieldSpec> {
        val basic = listOf(
            UciFieldSpec(
                "channel", R.string.wifi_field_channel, UciFieldType.SELECT,
                // auto 为静态首选项；freqlist 候选合并去重（静态在前）
                options = listOf(UciOption("", labelRes = R.string.wifi_option_auto)),
                candidates = UciCandidates.FREQLIST,
                groupRes = R.string.wifi_group_basic,
            ),
            UciFieldSpec(
                "htmode", R.string.wifi_field_htmode, UciFieldType.SELECT,
                options = HWMODES.map { UciOption(it, label = it) },
                candidates = UciCandidates.HTMODES,
                groupRes = R.string.wifi_group_basic,
            ),
            if (mtk) {
                UciFieldSpec(
                    "txpower", R.string.wifi_field_txpower, UciFieldType.TEXT,
                    pattern = "^(?:100|[1-9][0-9]?)$", patternErrorRes = R.string.wifi_error_percent,
                    placeholder = "1-100", hintRes = R.string.wifi_hint_percent,
                    groupRes = R.string.wifi_group_basic,
                )
            } else {
                UciFieldSpec(
                    "txpower", R.string.wifi_field_txpower, UciFieldType.SELECT,
                    options = listOf(UciOption("", labelRes = R.string.wifi_option_auto)),
                    candidates = UciCandidates.TXPOWERLIST,
                    groupRes = R.string.wifi_group_basic,
                )
            },
            UciFieldSpec(
                "country", R.string.wifi_field_country, UciFieldType.TEXT,
                // TEXT + 候选 = 输入框尾部下拉：可选可手填（自定义驱动国家码如 DB/ETSI）
                candidates = UciCandidates.COUNTRYLIST,
                placeholder = "CN",
                groupRes = R.string.wifi_group_basic,
            ),
        )
        if (!mtk) return basic
        val mtkGroup = R.string.wifi_group_mtk
        return basic + buildList {
            if (band2g) {
                add(UciFieldSpec("noscan", R.string.wifi_field_noscan, UciFieldType.SWITCH, groupRes = mtkGroup))
            }
            add(switchSpec("mu_beamformer", R.string.wifi_field_mu_beamformer, "1", mtkGroup))
            add(switchSpec("whnat", R.string.wifi_field_whnat, "1", mtkGroup))
            add(switchSpec("bandsteering", R.string.wifi_field_bandsteering, null, mtkGroup))
            add(
                UciFieldSpec(
                    "twt", R.string.wifi_field_twt, UciFieldType.SELECT,
                    options = listOf(
                        UciOption("", labelRes = R.string.wifi_twt_default),
                        UciOption("1", labelRes = R.string.wifi_twt_on),
                        UciOption("2", labelRes = R.string.wifi_twt_force),
                    ),
                    groupRes = mtkGroup,
                ),
            )
            add(
                UciFieldSpec(
                    "dtim_period", R.string.wifi_field_dtim, UciFieldType.TEXT,
                    pattern = "^(?:[1-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$",
                    patternErrorRes = R.string.wifi_error_dtim,
                    placeholder = "1-255", groupRes = mtkGroup,
                ),
            )
            add(
                UciFieldSpec(
                    "beacon_int", R.string.wifi_field_beacon, UciFieldType.TEXT,
                    pattern = "^(?:[2-9][0-9]|[1-9][0-9]{2})$",
                    patternErrorRes = R.string.wifi_error_beacon,
                    placeholder = "20-999", groupRes = mtkGroup,
                ),
            )
            add(switchSpec("wapp", R.string.wifi_field_wapp, null, mtkGroup))
        }
    }

    /** wifi-iface 编辑字段：通用组（既定字段白名单）+ MTK 高级组（13 字段 + wpa_group_rekey） */
    fun ifaceSchema(mtk: Boolean): List<UciFieldSpec> {
        val basic = listOf(
            UciFieldSpec(
                "ssid", R.string.wifi_ssid_label, UciFieldType.TEXT,
                required = true, pattern = "^.{1,32}$", patternErrorRes = R.string.wifi_error_ssid_len,
                groupRes = R.string.wifi_group_basic,
            ),
            UciFieldSpec(
                "encryption", R.string.wifi_field_encryption, UciFieldType.SELECT,
                default = "psk2",
                options = listOf(
                    UciOption("none", labelRes = R.string.network_no_encryption),
                    UciOption("owe", label = "OWE"),
                    UciOption("psk", labelRes = R.string.wifi_enc_psk),
                    UciOption("psk2", labelRes = R.string.wifi_enc_psk2),
                    UciOption("psk-mixed", labelRes = R.string.wifi_enc_psk_mixed),
                    UciOption("sae", labelRes = R.string.wifi_enc_sae),
                    UciOption("sae-mixed", labelRes = R.string.wifi_enc_sae_mixed),
                ),
                groupRes = R.string.wifi_group_basic,
            ),
            // 加密算法（luci-mtk cipher 枚举 auto/ccmp/tkip/tkip+ccmp）：uci 无独立 option，
            // 作为 encryption 的 `+后缀` 存储——读入拆解见 normalizeEntry，保存拼回见 mergeCipherSuffix
            UciFieldSpec(
                "cipher", R.string.wifi_field_cipher, UciFieldType.SELECT,
                options = listOf(
                    UciOption("", labelRes = R.string.wifi_cipher_auto),
                    UciOption("ccmp", label = "CCMP"),
                    UciOption("tkip", label = "TKIP"),
                    UciOption("tkip+ccmp", label = "TKIP+CCMP"),
                ),
                dependsKey = "encryption", dependsValues = KEYED_ENCRYPTIONS,
                groupRes = R.string.wifi_group_basic,
            ),
            UciFieldSpec(
                "key", R.string.wifi_field_key, UciFieldType.PASSWORD,
                dependsKey = "encryption", dependsValues = KEYED_ENCRYPTIONS,
                groupRes = R.string.wifi_group_basic,
            ),
            UciFieldSpec("hidden", R.string.wifi_field_hidden, UciFieldType.SWITCH, groupRes = R.string.wifi_group_basic),
            UciFieldSpec("isolate", R.string.wifi_field_isolate, UciFieldType.SWITCH, groupRes = R.string.wifi_group_basic),
        )
        if (!mtk) return basic
        val mtkGroup = R.string.wifi_group_mtk_iface
        return basic + listOf(
            UciFieldSpec(
                "mumimo_dl", R.string.wifi_field_mumimo_dl, UciFieldType.SWITCH,
                default = "1", hintRes = R.string.wifi_hint_mumimo, groupRes = mtkGroup,
            ),
            switchSpec("mumimo_ul", R.string.wifi_field_mumimo_ul, "1", mtkGroup),
            switchSpec("ofdma_dl", R.string.wifi_field_ofdma_dl, "1", mtkGroup),
            switchSpec("ofdma_ul", R.string.wifi_field_ofdma_ul, "1", mtkGroup),
            switchSpec("amsdu", R.string.wifi_field_amsdu, "1", mtkGroup),
            switchSpec("uapsd", R.string.wifi_field_uapsd, "1", mtkGroup),
            switchSpec("ieee80211k", R.string.wifi_field_80211k, "1", mtkGroup),
            switchSpec("autoba", R.string.wifi_field_autoba, "1", mtkGroup),
            switchSpec("ieee80211r", R.string.wifi_field_80211r, null, mtkGroup),
            UciFieldSpec(
                "kicklow", R.string.wifi_field_kicklow, UciFieldType.TEXT,
                pattern = DBM_PATTERN, patternErrorRes = R.string.wifi_error_dbm,
                placeholder = "-100 – 0", groupRes = mtkGroup,
            ),
            UciFieldSpec(
                "assocthres", R.string.wifi_field_assocthres, UciFieldType.TEXT,
                pattern = DBM_PATTERN, patternErrorRes = R.string.wifi_error_dbm,
                placeholder = "-100 – 0", groupRes = mtkGroup,
            ),
            UciFieldSpec(
                "steeringthresold", R.string.wifi_field_steering, UciFieldType.TEXT,
                pattern = DBM_PATTERN, patternErrorRes = R.string.wifi_error_dbm,
                placeholder = "-100 – 0", groupRes = mtkGroup,
            ),
            UciFieldSpec(
                "steeringbssid", R.string.wifi_field_steering_bssid, UciFieldType.DYNAMIC_LIST,
                pattern = "^(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$", patternErrorRes = R.string.wifi_error_mac,
                placeholder = "AA:BB:CC:DD:EE:FF", hintRes = R.string.wifi_hint_bssid_list, groupRes = mtkGroup,
            ),
            UciFieldSpec(
                "wpa_group_rekey", R.string.wifi_field_group_rekey, UciFieldType.TEXT,
                pattern = "^[1-9][0-9]{0,5}$", patternErrorRes = R.string.wifi_error_int,
                placeholder = "3600", groupRes = mtkGroup,
            ),
        )
    }

    /** MTK 开关简写（default=null = 关闭态，offValue '0'） */
    private fun switchSpec(key: String, @StringRes labelRes: Int, default: String?, groupRes: Int) =
        UciFieldSpec(key, labelRes, UciFieldType.SWITCH, default = default, groupRes = groupRes)

    /**
     * wifi-iface 读入归一：uci encryption 存储为
     * `模式+算法`（luci 写入如 psk2+ccmp），SELECT 只认纯模式。拆出后缀进 cipher 草稿；
     * 遗留别名映射与 luci-mtk cfgvalue 一致（aes→ccmp、tkip+aes/aes+tkip/ccmp+tkip→tkip+ccmp）。
     */
    fun normalizeEntry(entry: UciEntry): UciEntry {
        if (entry.type != IFACE_TYPE) return entry
        val raw = entry.first("encryption") ?: return entry
        if (raw.isEmpty()) return entry
        var suffix = if ('+' in raw) raw.substringAfter('+') else ""
        val mode = raw.substringBefore('+')
        when (suffix) {
            "aes" -> suffix = "ccmp"
            "tkip+aes", "aes+tkip", "ccmp+tkip" -> suffix = "tkip+ccmp"
        }
        return entry.copy(
            options = entry.options +
                ("encryption" to listOf(mode)) +
                ("cipher" to listOf(suffix)),
        )
    }

    /**
     * 保存序列化后把 cipher 拼回 encryption 后缀。
     * uci 无独立 cipher option；auto（空）= 裸模式——luci-mtk write 只对 ccmp/tkip/tkip+ccmp
     * 追加后缀同语义。encryption 依赖不满足（如 none）时 values 无 cipher 键，此函数为 no-op。
     */
    fun mergeCipherSuffix(values: JSONObject) {
        val cipher = values.optString("cipher")
        values.remove("cipher")
        if (!values.has("encryption") || cipher.isEmpty()) return
        values.put("encryption", "${values.getString("encryption")}+$cipher")
    }
}
