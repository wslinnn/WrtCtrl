package dev.wrtctrl.viewmodel.plugin

import org.json.JSONArray
import org.json.JSONObject

/**
 * UCI 插件 schema 层。
 * 与 LuCI 行为一致的逐行语义：
 * 值序列化（空列表不写/空串照写/multiSelect 按 uciList 分数组与空格串）、depends 隐藏不校验不提交、
 * switch 映射（on/off 值对 + invert）。纯 Kotlin 无 android 依赖，单测锚定。
 */

/** 编辑器字段类型（对应 LuCI 列表组件的 type） */
enum class UciFieldType {
    TEXT,
    PASSWORD,
    SWITCH,
    SELECT,
    DEVICE_SELECT,
    MULTI_SELECT,
    DYNAMIC_LIST,
}

/** 候选类别（kind 与 UciListViewModel.candidates map 的键一一对应） */
enum class UciCandidates(val kind: String) {
    HOSTHINTS_IP("hosthints-ip"),
    HOSTHINTS_MAC("hosthints-mac"),
    DEVICES("devices"),
    INTERFACES("interfaces"),
    ZONES("zones"),
    PRINTERS("printers"),

    /** conntrack helper（rule/zone 的 helper 引用） */
    HELPERS("helpers"),

    /** ipset 集合名（rule/redirect/nat 的「使用 ipset」引用） */
    IPSETS("ipsets"),

    /** 接口 IPv4（usb-printer bind：选接口写其 IP，对齐 LuCI network_netlist） */
    IFADDRS("ifaddrs"),
}

/** 下拉/候选单条。labelRes 用于静态选项的多语言（如 ACCEPT→接受）；动态候选用 label 原串 */
data class UciOption(val value: String, val labelRes: Int? = null, val label: String? = null)

/**
 * 字段规格。switch 映射：onValue/offValue（samba4 yes/no）优先写入；arpbind 反逻辑 = on="0" off="1"。
 * depends：dependsKey 的当前值 ∈ dependsValues 才显示/校验/提交（multiSelect 按集合交集，见 dependsMet）。
 */
data class UciFieldSpec(
    val key: String,
    val labelRes: Int,
    val type: UciFieldType,
    val required: Boolean = false,
    val default: String? = null,
    val options: List<UciOption> = emptyList(),
    val candidates: UciCandidates? = null,
    /** MULTI_SELECT 专有：true = 写 JSON 数组（uci list），false = 空格连接串写回（samba4 interface 语义） */
    val uciList: Boolean = false,
    val onValue: String = "1",
    val offValue: String = "0",
    val dependsKey: String? = null,
    val dependsValues: List<String> = emptyList(),
    val pattern: String? = null,
    val patternErrorRes: Int? = null,
    /** 分组标题（声明序中相邻同组聚合出 sechead；null = 无标题组，与 LuCI 分组语义一致） */
    val groupRes: Int? = null,
    /** 输入占位提示（原样串：示例值无翻译需求） */
    val placeholder: String? = null,
    /** MULTI_SELECT 专有：允许输入创建自定义值（如协议号 47）；自定义值原样写回 */
    val allowCustom: Boolean = false,
    /** 已有段禁改（zone/ipset 名称改名会留悬空引用，编辑已有条目时只读） */
    val readOnly: Boolean = false,
    /** 字段说明文案（如 samba 接口的「未指定接口则监听 lan」，双语键） */
    val hintRes: Int? = null,
)

/** uciGet 解析后的单个 section（options 归一：标量→单元素列表） */
data class UciEntry(
    val section: String,
    val type: String,
    val options: Map<String, List<String>>,
) {
    /** 标量取值（列表取首项——rpcd 对 list option 的读出按序，首项即旧 String(raw) 语义） */
    fun first(key: String): String? = options[key]?.firstOrNull()
}

/** detectlp 发现的 USB 打印机（usbPrinters 信封 data.details 单条） */
data class PrinterDetail(
    val devname: String,
    val product: String,
    val model: String,
    val description: String,
    val id: String,
    val devicePath: String,
)

/** luci.upnp get_status 的活跃映射（rules 数组单条；num 即 delete_rule 的 token） */
data class UpnpRuleUi(
    val num: String,
    val descr: String,
    val hostHint: String,
    val proto: String,
    val extPort: String,
    val intAddr: String,
    val intPort: String,
    /** 租约剩余秒数（0/缺失=不过期）；展示为倒计时 */
    val expires: Long = 0,
)

object UciParsers {

    /**
     * uciGet 信封 data（{sectionName: {name, section_type, anonymous, options:{…}}}）→ 条目列表。
     * 一切按 section 名排序保证确定性（org.json keys() HashMap 序纪律）。
     * type=null 不过滤（firewall 六类分流由调用方按 entry.type 分桶）。
     */
    fun entries(data: JSONObject, type: String? = null): List<UciEntry> {
        val list = ArrayList<UciEntry>()
        val keys = data.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            parseEntry(name, data.optJSONObject(name), type)?.let { list.add(it) }
        }
        return list.sortedBy { it.section }
    }

    /** 单 section 解析；缺 section_type / 类型不符返回 null（与 LuCI 的 .type 过滤一致） */
    private fun parseEntry(name: String, obj: JSONObject?, type: String?): UciEntry? {
        if (obj == null) return null
        val sectionType = obj.optString("section_type")
        if (sectionType.isEmpty()) return null
        if (type != null && sectionType != type) return null
        return UciEntry(name, sectionType, parseOptions(obj.optJSONObject("options") ?: JSONObject()))
    }

    private fun parseOptions(raw: JSONObject): Map<String, List<String>> {
        val options = HashMap<String, List<String>>()
        val keys = raw.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val arr = raw.optJSONArray(k)
            options[k] = if (arr != null) {
                List(arr.length()) { arr.optString(it) }
            } else {
                listOf(raw.optString(k))
            }
        }
        return options
    }

    /** candidates(kind) 数组 → 候选对 */
    fun candidates(arr: JSONArray?): List<UciOption> {
        if (arr == null) return emptyList()
        val list = ArrayList<UciOption>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val value = obj.optString("value")
            if (value.isEmpty()) continue
            list.add(UciOption(value, label = obj.optString("label")))
        }
        return list
    }

    /** hostHints 信封 data（{hosthintsMac:[…], hosthintsIp:[…]}）→ kind→候选 表 */
    fun hostHints(data: JSONObject): Map<String, List<UciOption>> = mapOf(
        UciCandidates.HOSTHINTS_MAC.kind to candidates(data.optJSONArray("hosthintsMac")),
        UciCandidates.HOSTHINTS_IP.kind to candidates(data.optJSONArray("hosthintsIp")),
    )

    /** usbPrinters 信封 data（{printers:[…], details:[…], error}）→ 发现明细（列表行展示用） */
    fun printerDetails(data: JSONObject): List<PrinterDetail> {
        val arr = data.optJSONArray("details") ?: return emptyList()
        val list = ArrayList<PrinterDetail>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            list.add(
                PrinterDetail(
                    devname = obj.optString("devname"),
                    product = obj.optString("product"),
                    model = obj.optString("model"),
                    description = obj.optString("description"),
                    id = obj.optString("id"),
                    devicePath = obj.optString("devicePath"),
                ),
            )
        }
        return list
    }

    /** luci.upnp get_status 信封 data（{rules:[…]}）→ 活跃映射列表（按取回序展示） */
    fun upnpRules(data: JSONObject): List<UpnpRuleUi> {
        val arr = data.optJSONArray("rules") ?: return emptyList()
        val list = ArrayList<UpnpRuleUi>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            list.add(
                UpnpRuleUi(
                    num = obj.optString("num"),
                    descr = obj.optString("descr"),
                    hostHint = obj.optString("host_hint"),
                    proto = obj.optString("proto"),
                    extPort = obj.optString("extport"),
                    intAddr = obj.optString("intaddr"),
                    intPort = obj.optString("intport"),
                    expires = obj.optLong("expires", 0),
                ),
            )
        }
        return list
    }
}

/** 编辑器值状态：标量字段（text/password/select/switch/multiSelect=空格串）与列表字段（dynamicList）分存 */
data class UciEditorDraft(
    val scalars: Map<String, String> = emptyMap(),
    val lists: Map<String, List<String>> = emptyMap(),
)

object UciEditorLogic {

    /** switch 当前 UI 态：scalars 值 == on 映射（invert 字段 on="0"，天然覆盖） */
    fun switchOn(spec: UciFieldSpec, draft: UciEditorDraft): Boolean =
        draft.scalars[spec.key] == spec.onValue

    /**
     * depends 判定（与 LuCI fieldDependsMet 一致）：依赖字段值 ∈ dependsValues；依赖字段为
     * multiSelect（空格连接串存储，如 proto='tcp udp'）时按空格拆分集合交集——
     * 标量与集合两种匹配合一，无需知道依赖字段的类型。
     */
    fun dependsMet(spec: UciFieldSpec, draft: UciEditorDraft): Boolean {
        val key = spec.dependsKey ?: return true
        val cur = draft.scalars[key] ?: return false
        return spec.dependsValues.any { it == cur || cur.split(' ').contains(it) }
    }

    /** 新建默认草稿：标量=default ?: ""（switch=default ?: offValue）；列表=空 */
    fun initialDraft(specs: List<UciFieldSpec>): UciEditorDraft {
        val scalars = HashMap<String, String>()
        val lists = HashMap<String, List<String>>()
        for (s in specs) {
            when (s.type) {
                UciFieldType.DYNAMIC_LIST -> lists[s.key] = emptyList()
                UciFieldType.MULTI_SELECT ->
                    scalars[s.key] = s.default?.split(' ')?.joinToString(" ") { it.trim() }?.trim() ?: ""
                UciFieldType.SWITCH -> scalars[s.key] = s.default ?: s.offValue
                else -> scalars[s.key] = s.default ?: ""
            }
        }
        return UciEditorDraft(scalars, lists)
    }

    /** 编辑草稿：设备值覆盖默认（缺省键保留默认）；multiSelect 列表按空格串入 scalars */
    fun draftFromEntry(specs: List<UciFieldSpec>, entry: UciEntry): UciEditorDraft {
        val base = initialDraft(specs)
        val scalars = base.scalars.toMutableMap()
        val lists = base.lists.toMutableMap()
        for (s in specs) {
            val raw = entry.options[s.key] ?: continue
            when (s.type) {
                UciFieldType.DYNAMIC_LIST -> lists[s.key] = raw
                UciFieldType.MULTI_SELECT -> scalars[s.key] = raw.joinToString(" ")
                else -> scalars[s.key] = raw.firstOrNull() ?: base.scalars[s.key].orEmpty()
            }
        }
        return UciEditorDraft(scalars, lists)
    }

    /**
     * 校验：返回第一个不合法字段（null = 通过）。depends 未满足跳过；
     * required 空值不通过（dynamicList 看列表空）；pattern 对非空值生效，dynamicList 逐元素。
     */
    fun firstInvalid(specs: List<UciFieldSpec>, draft: UciEditorDraft): UciFieldSpec? {
        for (spec in specs) {
            if (!dependsMet(spec, draft)) continue
            if (spec.type == UciFieldType.DYNAMIC_LIST) {
                val list = draft.lists[spec.key].orEmpty()
                if (spec.required && list.isEmpty()) return spec
                val re = spec.pattern?.let { Regex(it) }
                if (re != null && list.any { it.isNotEmpty() && !re.matches(it) }) return spec
            } else {
                val v = draft.scalars[spec.key].orEmpty()
                if (spec.required && v.isEmpty()) return spec
                val re = spec.pattern?.let { Regex(it) }
                if (re != null && v.isNotEmpty() && !re.matches(v)) return spec
            }
        }
        return null
    }

    /**
     * 值序列化（与 LuCI 保存序列化一致）：
     * depends 未满足不提交；dynamicList 非空才写数组；multiSelect uciList 非空才写数组、
     * 否则空格串；switch 写 on/off 映射；其余 String（空串照写）。
     */
    fun buildValues(specs: List<UciFieldSpec>, draft: UciEditorDraft): JSONObject {
        val values = JSONObject()
        for (spec in specs) {
            if (dependsMet(spec, draft)) appendValue(values, spec, draft)
        }
        return values
    }

    private fun appendValue(values: JSONObject, spec: UciFieldSpec, draft: UciEditorDraft) {
        when (spec.type) {
            UciFieldType.DYNAMIC_LIST -> {
                val list = draft.lists[spec.key].orEmpty()
                if (list.isNotEmpty()) values.put(spec.key, JSONArray(list))
            }
            UciFieldType.MULTI_SELECT -> {
                val list = draft.scalars[spec.key].orEmpty().split(' ').filter { it.isNotEmpty() }
                if (spec.uciList) {
                    if (list.isNotEmpty()) values.put(spec.key, JSONArray(list))
                } else {
                    values.put(spec.key, list.joinToString(" "))
                }
            }
            UciFieldType.SWITCH ->
                values.put(spec.key, if (switchOn(spec, draft)) spec.onValue else spec.offValue)
            else -> values.put(spec.key, draft.scalars[spec.key].orEmpty())
        }
    }

    /** 行内开关补丁值（只含 enabled 一键） */
    fun switchPatch(spec: UciFieldSpec, on: Boolean): JSONObject =
        JSONObject().put(spec.key, if (on) spec.onValue else spec.offValue)

    private const val CRON_STEP = "(?:/[1-9][0-9]*)?"

    /**
     * crontab 列表表达式 pattern（对齐 LuCI autoreboot validate）：单值 / 范围(min-max) /
     * 步进（星号斜杠n，步进对「星号」与「数值段」均可接） / 列表(1,3,5)。
     * 数字段必须显式枚举原子（[0-23] 是字符类不是数值范围）。
     */
    fun cronListPattern(atom: String): String {
        val item = "(?:\\*|(?:$atom))(?:$CRON_STEP)?"
        return "^(?:$item)(?:,(?:$item))*$"
    }

    private val WOL_CRON_FIELD_ATOMS = listOf(
        "[1-5]?[0-9](?:-[1-5]?[0-9])?",                              // 分 0-59
        "(?:1?[0-9]|2[0-3])(?:-(?:1?[0-9]|2[0-3]))?",                // 时 0-23
        "(?:[1-9]|[12][0-9]|3[01])(?:-(?:[1-9]|[12][0-9]|3[01]))?",  // 日 1-31
        "(?:[1-9]|1[0-2])(?:-(?:[1-9]|1[0-2]))?",                    // 月 1-12
        "[0-6](?:-[0-6])?",                                          // 周 0-6
    )

    /** wolultra 整条 cron 表达式 pattern（5 段，段值范围各异，段内支持范围/步进/列表） */
    fun wolCronPattern(): String {
        val fields = WOL_CRON_FIELD_ATOMS.map { atom ->
            // 步进接在「星号|数值段」整体之后（*/2 与 1-5/2 均合法）
            val item = "(?:\\*|(?:$atom))(?:$CRON_STEP)?"
            "(?:$item)(?:,(?:$item))*"
        }
        return "^${fields.joinToString(" ")}$"
    }

    /**
     * wolultra 定时的实际生效表达式（对齐 LuCI cronExpression/init.d append_rule：
     * **cron 字段优先**，缺失回落五字段拼接）。
     */
    fun wolCronExpression(entry: UciEntry): String =
        entry.first("cron")?.takeIf { it.isNotBlank() }
            ?: listOf(
                entry.first("minute") ?: "0",
                entry.first("hour") ?: "0",
                entry.first("day") ?: "*",
                entry.first("month") ?: "*",
                entry.first("weeks") ?: "*",
            ).joinToString(" ")
}
