package dev.wrtctrl.viewmodel.plugin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UCI 插件 schema 层单测：解析形状对齐 Rust uci_get 类型化信封
 * （{section: {name, section_type, anonymous, options}}），编辑器语义与 LuCI uci 列表组件语义一致。
 */
class UciSchemaTest {

    // ── UciParsers.entries ──

    @Test
    fun `entries parse scalar and list options sorted by section`() {
        val data = JSONObject()
            .put(
                "cfg0b5c2d",
                JSONObject()
                    .put("section_type", "host")
                    .put(
                        "options",
                        JSONObject().put("mac", "00:11:22:33:44:55").put("name", JSONArray().put("a").put("b")),
                    ),
            )
            .put(
                "cfg0a1b2c",
                JSONObject().put("section_type", "host").put("options", JSONObject().put("ip", "192.168.1.2")),
            )
            .put(
                "other_type",
                JSONObject().put("section_type", "rule").put("options", JSONObject().put("x", "1")),
            )
        val hosts = UciParsers.entries(data, "host")
        assertEquals(listOf("cfg0a1b2c", "cfg0b5c2d"), hosts.map { it.section })
        assertEquals(listOf("192.168.1.2"), hosts[0].options["ip"])
        assertEquals("00:11:22:33:44:55", hosts[1].first("mac"))
        assertEquals(listOf("a", "b"), hosts[1].options["name"])
        // 全量不过滤
        assertEquals(3, UciParsers.entries(data).size)
    }

    @Test
    fun `entries skip malformed sections`() {
        val data = JSONObject()
            .put("no_type", JSONObject().put("options", JSONObject()))
            .put("not_object", "junk")
            .put("ok", JSONObject().put("section_type", "t").put("options", JSONObject().put("k", "v")))
        val entries = UciParsers.entries(data)
        assertEquals(1, entries.size)
        assertEquals("ok", entries[0].section)
    }

    // ── 草稿初始化 ──

    private fun spec(
        key: String,
        type: UciFieldType,
        default: String? = null,
        onValue: String = "1",
        offValue: String = "0",
    ) = UciFieldSpec(key, 0, type, default = default, onValue = onValue, offValue = offValue)

    @Test
    fun `initialDraft applies defaults and switch off value`() {
        val specs = listOf(
            spec("a", UciFieldType.TEXT, default = "x"),
            spec("b", UciFieldType.TEXT),
            spec("on", UciFieldType.SWITCH, default = "1"),
            spec("sw", UciFieldType.SWITCH),
            spec("yes", UciFieldType.SWITCH, default = "yes", onValue = "yes", offValue = "no"),
            spec("list", UciFieldType.DYNAMIC_LIST),
        )
        val draft = UciEditorLogic.initialDraft(specs)
        assertEquals("x", draft.scalars["a"])
        assertEquals("", draft.scalars["b"])
        assertEquals("1", draft.scalars["on"])
        assertEquals("0", draft.scalars["sw"])
        assertEquals("yes", draft.scalars["yes"])
        assertEquals(emptyList<String>(), draft.lists["list"])
    }

    @Test
    fun `draftFromEntry overrides from device values`() {
        val entry = UciEntry(
            section = "s1",
            type = "t",
            options = mapOf("name" to listOf("dev"), "tags" to listOf("a", "b"), "enabled" to listOf("1")),
        )
        val specs = listOf(
            spec("name", UciFieldType.TEXT, default = ""),
            spec("tags", UciFieldType.DYNAMIC_LIST),
            spec("enabled", UciFieldType.SWITCH),
        )
        val draft = UciEditorLogic.draftFromEntry(specs, entry)
        assertEquals("dev", draft.scalars["name"])
        assertEquals(listOf("a", "b"), draft.lists["tags"])
        assertEquals("1", draft.scalars["enabled"])
    }

    // ── depends ──

    @Test
    fun `dependsMet scalar and multiSelect intersection`() {
        val specs = listOf(spec("proto", UciFieldType.MULTI_SELECT), spec("port", UciFieldType.TEXT))
        val draft = UciEditorDraft(scalars = mapOf("proto" to "tcp udp", "port" to ""), lists = emptyMap())
        val dep = UciFieldSpec("x", 0, UciFieldType.TEXT, dependsKey = "proto", dependsValues = listOf("tcp", "udp"))
        assertTrue(UciEditorLogic.dependsMet(dep, draft))
        val icmpDraft = draft.copy(scalars = draft.scalars + ("proto" to "icmp"))
        assertFalse(UciEditorLogic.dependsMet(dep, icmpDraft))
        val missing = draft.copy(scalars = draft.scalars - "proto")
        assertFalse(UciEditorLogic.dependsMet(dep, missing))
        val noDep = UciFieldSpec("y", 0, UciFieldType.TEXT)
        assertTrue(UciEditorLogic.dependsMet(noDep, missing))
    }

    // ── 校验 ──

    @Test
    fun `firstInvalid required pattern and dynamicList element check`() {
        val specs = listOf(
            UciFieldSpec("req", 1, UciFieldType.TEXT, required = true),
            UciFieldSpec("mac", 2, UciFieldType.TEXT, pattern = "^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$"),
            UciFieldSpec("ips", 3, UciFieldType.DYNAMIC_LIST, pattern = "^[0-9.]+$"),
            UciFieldSpec("freedyn", 4, UciFieldType.DYNAMIC_LIST, required = true),
        )
        val empty = UciEditorLogic.initialDraft(specs)
        assertEquals("req", UciEditorLogic.firstInvalid(specs, empty)?.key)

        val filled = UciEditorDraft(
            scalars = mapOf("req" to "v", "mac" to "00:11:22:33:44:55"),
            lists = mapOf("ips" to listOf("10.0.0.1"), "freedyn" to listOf("x")),
        )
        assertNull(UciEditorLogic.firstInvalid(specs, filled))

        val badMac = filled.copy(scalars = filled.scalars + ("mac" to "zz"))
        assertEquals("mac", UciEditorLogic.firstInvalid(specs, badMac)?.key)

        val badIp = filled.copy(lists = filled.lists + ("ips" to listOf("ok", "bad!")))
        assertEquals("ips", UciEditorLogic.firstInvalid(specs, badIp)?.key)
    }

    @Test
    fun `firstInvalid skips depends unmet fields`() {
        val specs = listOf(
            UciFieldSpec("snat_ip", 5, UciFieldType.TEXT, required = true, dependsKey = "target", dependsValues = listOf("SNAT")),
        )
        val draft = UciEditorDraft(scalars = mapOf("target" to "MASQUERADE"), lists = emptyMap())
        assertNull(UciEditorLogic.firstInvalid(specs, draft))
    }

    // ── 值序列化（与 LuCI 保存序列化一致） ──

    @Test
    fun `buildValues switch mappings including invert and yes-no`() {
        val specs = listOf(
            UciFieldSpec("enabled", 6, UciFieldType.SWITCH, default = "0", onValue = "0", offValue = "1"),
            UciFieldSpec("browseable", 7, UciFieldType.SWITCH, onValue = "yes", offValue = "no", default = "yes"),
        )
        val on = UciEditorDraft(scalars = mapOf("enabled" to "0", "browseable" to "yes"), lists = emptyMap())
        val values = UciEditorLogic.buildValues(specs, on)
        assertEquals("0", values.getString("enabled"))
        assertEquals("yes", values.getString("browseable"))
        val patch = UciEditorLogic.switchPatch(specs[0], on = false)
        assertEquals("1", patch.getString("enabled"))
    }

    @Test
    fun `buildValues lists written as arrays only when non-empty`() {
        val specs = listOf(
            UciFieldSpec("tags", 8, UciFieldType.DYNAMIC_LIST),
            UciFieldSpec("net", 9, UciFieldType.MULTI_SELECT, uciList = true),
            UciFieldSpec("iface", 10, UciFieldType.MULTI_SELECT, uciList = false),
        )
        val draft = UciEditorDraft(
            scalars = mapOf("net" to "lan wan", "iface" to "lan wan"),
            lists = mapOf("tags" to listOf("soft", "noforceuid")),
        )
        val values = UciEditorLogic.buildValues(specs, draft)
        assertEquals(listOf("soft", "noforceuid"), values.getJSONArray("tags").let { a -> List(a.length()) { a.getString(it) } })
        assertEquals(listOf("lan", "wan"), values.getJSONArray("net").let { a -> List(a.length()) { a.getString(it) } })
        // 非 uciList multiSelect 写空格串（samba4 interface 语义）
        assertEquals("lan wan", values.getString("iface"))

        // 空列表全部跳过不写（rpcd 拒绝空数组；无法清空已存 list 为已知限制）
        val empty = UciEditorLogic.initialDraft(specs)
        val values2 = UciEditorLogic.buildValues(specs, empty)
        assertFalse(values2.has("tags"))
        assertFalse(values2.has("net"))
    }

    @Test
    fun `buildValues skips depends unmet and writes empty scalars`() {
        val specs = listOf(
            UciFieldSpec("a", 11, UciFieldType.TEXT, dependsKey = "gate", dependsValues = listOf("1")),
            UciFieldSpec("b", 12, UciFieldType.TEXT),
        )
        val draft = UciEditorDraft(scalars = mapOf("gate" to "0", "b" to ""), lists = emptyMap())
        val values = UciEditorLogic.buildValues(specs, draft)
        assertFalse(values.has("a"))
        assertTrue(values.has("b"))
        assertEquals("", values.getString("b"))
    }

    // ── wolultra cron──

    @Test
    fun `wolCronExpression prefers cron field and falls back to five fields`() {
        val withCron = UciEntry("s1", "macclient", mapOf("cron" to listOf("30 8 * * 1-5")))
        assertEquals("30 8 * * 1-5", UciEditorLogic.wolCronExpression(withCron))
        val legacy = UciEntry(
            "s2", "macclient",
            mapOf("minute" to listOf("0"), "hour" to listOf("2"), "day" to listOf("*")),
        )
        assertEquals("0 2 * * *", UciEditorLogic.wolCronExpression(legacy))
    }

    @Test
    fun `wolCronPattern accepts composite expressions and rejects out-of-range`() {
        val re = Regex(UciEditorLogic.wolCronPattern())
        // 合法：默认值 / 范围 / 步进 / 列表 / 混合
        listOf(
            "0 0 * * *",
            "30 8 * * 1-5",
            "*/2 */6 * * *",
            "0,15,30,45 0-23/2 1,15 * 0-6",
            "5 3 1,15 1-12 *",
        ).forEach { assertTrue("accept: $it", re.matches(it)) }
        // 非法：越界 / 段数错 / 空步进
        listOf(
            "60 0 * * *",
            "0 24 * * *",
            "0 0 32 * *",
            "0 0 * 13 *",
            "0 0 * * 7",
            "0 0 * *",
            "* * * * * *",
            "0 0 * * 1/",
        ).forEach { assertFalse("reject: $it", re.matches(it)) }
    }

    @Test
    fun `autoreboot cronListPattern accepts ranges steps lists`() {
        val week = Regex(UciEditorLogic.cronListPattern("[0-6](?:-[0-6])?"))
        listOf("*", "1", "1-5", "*/2", "1,3,5", "1-5/2").forEach { assertTrue("accept: $it", week.matches(it)) }
        listOf("7", "1-7", "", "1,", "1//2", "1-").forEach { assertFalse("reject: $it", week.matches(it)) }
    }

    // ── 候选/发现解析 ──

    @Test
    fun `candidates and hostHints parse`() {
        val arr = JSONArray().put(JSONObject().put("value", "br-lan").put("label", "br-lan (LAN)"))
        val opts = UciParsers.candidates(arr)
        assertEquals(1, opts.size)
        assertEquals("br-lan", opts[0].value)
        assertEquals("br-lan (LAN)", opts[0].label)

        val hints = UciParsers.hostHints(
            JSONObject()
                .put(
                    "hosthintsMac",
                    JSONArray().put(JSONObject().put("value", "AA:BB").put("label", "AA:BB (dev)")),
                )
                .put("hosthintsIp", JSONArray().put(JSONObject().put("value", "192.168.1.5").put("label", "192.168.1.5"))),
        )
        assertEquals(1, hints[UciCandidates.HOSTHINTS_MAC.kind]!!.size)
        assertEquals("192.168.1.5", hints[UciCandidates.HOSTHINTS_IP.kind]!![0].value)
        assertNotNull(UciParsers.candidates(null).isEmpty())
    }

    @Test
    fun `upnpRules and printerDetails parse`() {
        val rules = UciParsers.upnpRules(
            JSONObject().put(
                "rules",
                JSONArray().put(
                    JSONObject()
                        .put("num", "3")
                        .put("descr", "PS5")
                        .put("host_hint", "host")
                        .put("proto", "UDP")
                        .put("extport", "3074")
                        .put("intaddr", "192.168.1.50")
                        .put("intport", "3074"),
                ),
            ),
        )
        assertEquals(1, rules.size)
        assertEquals("3", rules[0].num)
        assertEquals("3074", rules[0].extPort)

        val printers = UciParsers.printerDetails(
            JSONObject().put(
                "details",
                JSONArray().put(
                    JSONObject()
                        .put("devname", "lp0")
                        .put("product", "03f0/2b17/100")
                        .put("model", "LaserJet")
                        .put("description", "HP LaserJet")
                        .put("id", "03f0:2b17")
                        .put("devicePath", "/dev/usb/lp0"),
                ),
            ),
        )
        assertEquals(1, printers.size)
        assertEquals("03f0:2b17", printers[0].id)
        assertEquals("/dev/usb/lp0", printers[0].devicePath)
    }
}
