package dev.wrtctrl.viewmodel

import dev.wrtctrl.viewmodel.plugin.UciEditorDraft
import dev.wrtctrl.viewmodel.plugin.UciEditorLogic
import dev.wrtctrl.viewmodel.plugin.UciEntry
import dev.wrtctrl.viewmodel.plugin.UciFieldType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * wifi 编辑器 schema 测试：字段集/默认值/depends/校验/序列化锚定。
 * schema 契约 = 既定字段白名单 ∪ MTK 插件设备级组。
 */
class WifiSchemasTest {

    @Test
    fun `radio schema——mtk 2g 含 noscan 与 MTK 设备组、非 mtk 仅通用组、txpower 按 type 分支`() {
        val mtk2g = WifiSchemas.radioSchema(mtk = true, band2g = true)
        assertEquals(
            listOf(
                "channel", "htmode", "txpower", "country", "noscan",
                "mu_beamformer", "whnat", "bandsteering", "twt", "dtim_period", "beacon_int", "wapp",
            ),
            mtk2g.map { it.key },
        )
        // 5G 不渲染 noscan（构建期过滤，band 不是可编辑字段）
        assertEquals(false, WifiSchemas.radioSchema(mtk = true, band2g = false).any { it.key == "noscan" })
        // 非 mtk：仅通用组，txpower 走 SELECT + txpowerlist 候选
        val plain = WifiSchemas.radioSchema(mtk = false, band2g = true)
        assertEquals(listOf("channel", "htmode", "txpower", "country"), plain.map { it.key })
        val txSelect = plain.first { it.key == "txpower" }
        assertEquals(UciFieldType.SELECT, txSelect.type)
        assertEquals("txpowerlist", txSelect.candidates?.kind)
        // mtk：txpower 降级 TEXT + 百分比范围校验
        val txText = mtk2g.first { it.key == "txpower" }
        assertEquals(UciFieldType.TEXT, txText.type)
        val txRe = Regex(requireNotNull(txText.pattern))
        assertTrue(txRe.matches("100"))
        assertTrue(txRe.matches("60"))
        assertFalse(txRe.matches("101"))
        assertFalse(txRe.matches("0"))
        // twt 静态选项：默认值为空串（默认=禁用）
        val twt = mtk2g.first { it.key == "twt" }
        assertEquals(listOf("", "1", "2"), twt.options.map { it.value })
        // htmode 静态白名单上限 HE160
        val htmode = mtk2g.first { it.key == "htmode" }
        assertEquals("HE160", htmode.options.last().value)
    }

    @Test
    fun `radio 草稿与序列化——设备值覆盖默认、开关写 on-off 映射、twt 空串照写`() {
        val schema = WifiSchemas.radioSchema(mtk = true, band2g = false)
        val entry = UciEntry(
            section = "0_0_1",
            type = "wifi-device",
            options = mapOf(
                "type" to listOf("mtwifi"),
                "band" to listOf("5g"),
                "channel" to listOf("36"),
                "mu_beamformer" to listOf("0"),
            ),
        )
        val draft = UciEditorLogic.draftFromEntry(schema, entry)
        assertEquals("36", draft.scalars["channel"])
        assertEquals("0", draft.scalars["mu_beamformer"]) // 设备值覆盖默认 '1'
        assertEquals("1", draft.scalars["whnat"]) // 未设置回落默认
        assertEquals("", draft.scalars["twt"])
        val values = UciEditorLogic.buildValues(schema, draft)
        assertEquals("36", values.getString("channel"))
        assertEquals("0", values.getString("mu_beamformer"))
        assertEquals("", values.getString("twt"))
        assertEquals("0", values.getString("bandsteering")) // switch 关 → offValue
    }

    @Test
    fun `iface schema——key 依赖加密方式、mtk 高级组默认值、ssid 长度校验`() {
        val schema = WifiSchemas.ifaceSchema(mtk = true)
        val mtkKeys = schema.map { it.key }
        val mtkExpected = listOf(
            "mumimo_dl", "mumimo_ul", "ofdma_dl", "ofdma_ul", "amsdu", "uapsd", "ieee80211k",
            "autoba", "ieee80211r", "kicklow", "assocthres", "steeringthresold", "steeringbssid", "wpa_group_rekey",
        )
        assertTrue("MTK 高级组 13+1 字段齐", mtkKeys.containsAll(mtkExpected))
        val key = schema.first { it.key == "key" }
        val noneDraft = UciEditorDraft(scalars = mapOf("encryption" to "none", "key" to "old"))
        assertFalse("encryption=none 时 key 不显示", UciEditorLogic.dependsMet(key, noneDraft))
        // depends 未满足不提交（旧密码残留 uci 是已知收敛，构建的 values 不含键）
        val noneValues = UciEditorLogic.buildValues(schema, noneDraft)
        assertFalse(noneValues.has("key"))
        val pskDraft = UciEditorDraft(scalars = mapOf("encryption" to "psk2", "key" to "secret"))
        assertTrue(UciEditorLogic.dependsMet(key, pskDraft))
        assertEquals("secret", UciEditorLogic.buildValues(schema, pskDraft).getString("key"))
        // ssid：required 空 + pattern 超长都拦
        val ssid = schema.first { it.key == "ssid" }
        val emptyDraft = UciEditorDraft(scalars = mapOf("ssid" to ""))
        assertEquals(ssid, UciEditorLogic.firstInvalid(schema, emptyDraft))
        val longDraft = UciEditorDraft(scalars = mapOf("ssid" to "x".repeat(33)))
        assertEquals(ssid, UciEditorLogic.firstInvalid(schema, longDraft))
        assertNull(UciEditorLogic.firstInvalid(schema, UciEditorDraft(scalars = mapOf("ssid" to "Home-2G"))))
    }

    @Test
    fun `cipher 算法——后缀拆解回读、aes 遗留映射、保存拼回、none 与 auto 无后缀`() {
        val schema = WifiSchemas.ifaceSchema(mtk = true)
        val cipher = schema.first { it.key == "cipher" }
        assertEquals(listOf("", "ccmp", "tkip", "tkip+ccmp"), cipher.options.map { it.value })
        // cipher 与 key 同依赖：keyed 模式才显示/提交
        assertEquals(schema.first { it.key == "key" }.dependsValues, cipher.dependsValues)
        // psk2+ccmp → 纯模式 + 算法草稿
        val entry = UciEntry("wifinet1", "wifi-iface", mapOf("encryption" to listOf("psk2+ccmp")))
        val normalized = WifiSchemas.normalizeEntry(entry)
        assertEquals("psk2", normalized.first("encryption"))
        assertEquals("ccmp", normalized.first("cipher"))
        // 遗留别名（与 luci-mtk cfgvalue 一致）
        val legacy = WifiSchemas.normalizeEntry(
            UciEntry("w", "wifi-iface", mapOf("encryption" to listOf("psk2+aes"))),
        )
        assertEquals("ccmp", legacy.first("cipher"))
        val legacy2 = WifiSchemas.normalizeEntry(
            UciEntry("w", "wifi-iface", mapOf("encryption" to listOf("psk2+tkip+aes"))),
        )
        assertEquals("tkip+ccmp", legacy2.first("cipher"))
        // 草稿 → 序列化 → 拼回：往返稳定
        val draft = UciEditorLogic.draftFromEntry(schema, normalized)
        assertEquals("ccmp", draft.scalars["cipher"])
        val values = UciEditorLogic.buildValues(schema, draft)
        WifiSchemas.mergeCipherSuffix(values)
        assertEquals("psk2+ccmp", values.getString("encryption"))
        assertFalse(values.has("cipher"))
        // auto（无算法草稿）→ 裸模式
        val autoValues = UciEditorLogic.buildValues(
            schema,
            UciEditorDraft(scalars = mapOf("ssid" to "x", "encryption" to "psk2")),
        )
        WifiSchemas.mergeCipherSuffix(autoValues)
        assertEquals("psk2", autoValues.getString("encryption"))
        // none：cipher 依赖不满足不提交，拼回为 no-op
        val noneValues = UciEditorLogic.buildValues(
            schema,
            UciEditorDraft(scalars = mapOf("ssid" to "x", "encryption" to "none")),
        )
        WifiSchemas.mergeCipherSuffix(noneValues)
        assertEquals("none", noneValues.getString("encryption"))
    }

    @Test
    fun `dbm 阈值与 MAC 列表校验——负100到0 整数、dynamicList 逐元素`() {
        val schema = WifiSchemas.ifaceSchema(mtk = true)
        val kicklow = schema.first { it.key == "kicklow" }
        val re = Regex(kicklow.pattern!!)
        assertTrue(re.matches("0"))
        assertTrue(re.matches("-75"))
        assertTrue(re.matches("-100"))
        assertFalse(re.matches("1"))
        assertFalse(re.matches("-101"))
        assertFalse(re.matches("abc"))
        val steering = schema.first { it.key == "steeringbssid" }
        val macRe = Regex(steering.pattern!!)
        assertTrue(macRe.matches("AA:BB:CC:DD:EE:FF"))
        assertFalse(macRe.matches("AA:BB:CC:DD:EE"))
        val badList = UciEditorDraft(
            scalars = mapOf("ssid" to "ok"),
            lists = mapOf("steeringbssid" to listOf("AA:BB:CC:DD:EE:FF", "nope")),
        )
        assertEquals(steering, UciEditorLogic.firstInvalid(schema, badList))
    }
}
