@file:Suppress("TooManyFunctions")

package dev.wrtctrl.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.app.Application
import android.widget.Toast
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.wrtctrl.R
import dev.wrtctrl.util.FirewallColors
import dev.wrtctrl.viewmodel.plugin.FirewallViewModel
import dev.wrtctrl.viewmodel.plugin.UciCandidates
import dev.wrtctrl.viewmodel.plugin.UciEditorLogic
import dev.wrtctrl.viewmodel.plugin.UciEntry
import dev.wrtctrl.viewmodel.plugin.UciFieldSpec
import dev.wrtctrl.viewmodel.plugin.UciFieldType
import dev.wrtctrl.viewmodel.plugin.UciOption

/**
 * 防火墙页：常规设置(defaults) / 区域(zone) / 区域间转发(forwarding) /
 * 端口转发(redirect) / 流量规则(rule) / SNAT(nat) 六类段一次 uciGet 分流 + 自定义规则
 * （/etc/firewall.user 文本编辑，writeFile→restart）。所有 uci 编辑保存 = 高危红字确认。
 * 行摘要/zone 色点字段与 LuCI 防火墙页对齐。
 * TooManyFunctions：六类段行/摘要/编辑器定义按类各一组，聚合单文件是领域内聚而非杂堆。
 */

private const val FW_PORT_PATTERN = "^!?\\s*\\d{1,5}(-\\d{1,5})?$"
private const val FW_IP_PATTERN = "^!?\\s*[0-9a-fA-F:.]+(/\\d{1,3})?$"

private val FwPolicyOptions = listOf(
    UciOption("ACCEPT", R.string.firewall_opt_accept),
    UciOption("DROP", R.string.firewall_opt_drop),
    UciOption("REJECT", R.string.firewall_opt_reject),
)

private val FwProtoOptions = listOf(
    UciOption("all", R.string.firewall_opt_proto_any),
    UciOption("tcp", label = "TCP"),
    UciOption("udp", label = "UDP"),
    UciOption("icmp", label = "ICMP"),
    UciOption("icmpv6", label = "ICMPv6"),
)

private val FwFamilyOptions = listOf(
    UciOption("", R.string.firewall_family_any),
    UciOption("ipv4", R.string.firewall_family_ipv4),
    UciOption("ipv6", R.string.firewall_family_ipv6),
)

/** redirect 的地址族（fw4 语义：空=按 zone/IP 自动推断） */
private val FwRedirectFamilyOptions = listOf(
    UciOption("", R.string.firewall_family_auto),
    UciOption("any", R.string.firewall_family_any),
    UciOption("ipv4", R.string.firewall_family_ipv4),
    UciOption("ipv6", R.string.firewall_family_ipv6),
)

private val FwTargetOptions = listOf(
    UciOption("ACCEPT", R.string.firewall_opt_accept),
    UciOption("DROP", R.string.firewall_opt_drop),
    UciOption("REJECT", R.string.firewall_opt_reject),
    UciOption("NOTRACK", R.string.firewall_opt_notrack),
    UciOption("HELPER", R.string.firewall_target_helper),
    UciOption("MARK", R.string.firewall_target_mark),
    UciOption("DSCP", R.string.firewall_target_dscp),
)

private val FwNatTargetOptions = listOf(
    UciOption("MASQUERADE", R.string.firewall_opt_masquerade),
    UciOption("SNAT", R.string.firewall_opt_snat),
    UciOption("ACCEPT", R.string.firewall_opt_no_rewrite),
)

/** ICMP 类型全集（对齐 LuCI rules.js MultiValue；label=值本身，无需翻译） */
private val FwIcmpTypes = listOf(
    "address-mask-reply", "address-mask-request", "address-unreachable", "bad-header",
    "certification-path-solicitation-message", "certification-path-advertisement-message",
    "communication-prohibited", "destination-unreachable", "duplicate-address-request",
    "duplicate-address-confirmation", "echo-reply", "echo-request", "extended-echo-request",
    "extended-echo-reply", "fmipv6-message", "fragmentation-needed",
    "home-agent-address-discovery-reply-message", "home-agent-address-discovery-request-message",
    "host-precedence-violation", "host-prohibited", "host-redirect", "host-unknown",
    "host-unreachable", "ilnpv6-locator-update-message",
    "inverse-neighbour-discovery-advertisement-message",
    "inverse-neighbour-discovery-solicitation-message", "ip-header-bad",
    "mobile-prefix-advertisement", "mobile-prefix-solicitation", "mpl-control-message",
    "multicast-listener-query", "multicast-listener-report", "multicast-listener-done",
    "multicast-router-advertisement", "multicast-router-solicitation",
    "multicast-router-termination", "neighbour-advertisement", "neighbour-solicitation",
    "network-prohibited", "network-redirect", "network-unknown", "network-unreachable",
    "no-route", "node-info-query", "node-info-response", "packet-too-big",
    "parameter-problem", "port-unreachable", "precedence-cutoff", "protocol-unreachable",
    "redirect", "required-option-missing", "router-advertisement", "router-renumbering",
    "router-solicitation", "rpl-control-message", "source-quench", "source-route-failed",
    "time-exceeded", "timestamp-reply", "timestamp-request", "TOS-host-redirect",
    "TOS-host-unreachable", "TOS-network-redirect", "TOS-network-unreachable",
    "ttl-zero-during-reassembly", "ttl-zero-during-transit", "v2-multicast-listener-report",
    "unknown-header-type", "unknown-option",
).map { UciOption(it, label = it) }

private val FwWeekdayOptions = listOf(
    UciOption("Sun", R.string.autoreboot_sun),
    UciOption("Mon", R.string.autoreboot_mon),
    UciOption("Tue", R.string.autoreboot_tue),
    UciOption("Wed", R.string.autoreboot_wed),
    UciOption("Thu", R.string.autoreboot_thu),
    UciOption("Fri", R.string.autoreboot_fri),
    UciOption("Sat", R.string.autoreboot_sat),
)

/** 原版 timed tab 七件套（rule/snat 共用）：weekdays/monthdays 写空格串（uci list 兼容） */
private fun fwTimedFields(): List<UciFieldSpec> = listOf(
    UciFieldSpec(
        "weekdays", R.string.firewall_timed_weekdays, UciFieldType.MULTI_SELECT, uciList = true,
        options = FwWeekdayOptions, groupRes = R.string.firewall_group_timed,
    ),
    UciFieldSpec(
        "monthdays", R.string.firewall_timed_monthdays, UciFieldType.MULTI_SELECT, uciList = true,
        options = (1..31).map { UciOption(it.toString(), label = it.toString()) },
        groupRes = R.string.firewall_group_timed,
    ),
    UciFieldSpec(
        "start_time", R.string.firewall_timed_start_time, UciFieldType.TEXT,
        pattern = "^([01]?[0-9]|2[0-3]):[0-5][0-9](?::[0-5][0-9])?$", groupRes = R.string.firewall_group_timed,
    ),
    UciFieldSpec(
        "stop_time", R.string.firewall_timed_stop_time, UciFieldType.TEXT,
        pattern = "^([01]?[0-9]|2[0-3]):[0-5][0-9](?::[0-5][0-9])?$", groupRes = R.string.firewall_group_timed,
    ),
    UciFieldSpec(
        "start_date", R.string.firewall_timed_start_date, UciFieldType.TEXT,
        pattern = "^[0-9]{4}-[0-9]{2}-[0-9]{2}$", groupRes = R.string.firewall_group_timed,
    ),
    UciFieldSpec(
        "stop_date", R.string.firewall_timed_stop_date, UciFieldType.TEXT,
        pattern = "^[0-9]{4}-[0-9]{2}-[0-9]{2}$", groupRes = R.string.firewall_group_timed,
    ),
    UciFieldSpec(
        "utc_time", R.string.firewall_timed_utc, UciFieldType.SWITCH,
        groupRes = R.string.firewall_group_timed,
    ),
)

/** ipset 集合编辑（fw4：name/comment/family/match/entry/maxelem；fw3 字段不迁移） */
private val FwIpsetMatchOptions = listOf(
    "ip", "port", "mac", "net", "src_ip", "src_port", "src_mac", "src_net",
    "dest_ip", "dest_port", "dest_mac", "dest_net",
).map { UciOption(it, label = it) }

private val FwIpsetSchema = listOf(
    UciFieldSpec(
        "name", R.string.firewall_field_name, UciFieldType.TEXT, required = true, readOnly = true,
        pattern = "^[a-zA-Z_.][a-zA-Z0-9/_.-]*$", groupRes = R.string.firewall_general,
    ),
    UciFieldSpec("comment", R.string.firewall_ipsets_comment, UciFieldType.TEXT, groupRes = R.string.firewall_general),
    UciFieldSpec(
        "family", R.string.firewall_field_family, UciFieldType.SELECT, default = "ipv4",
        options = listOf(
            UciOption("any", R.string.firewall_family_any),
            UciOption("ipv4", R.string.firewall_family_ipv4),
            UciOption("ipv6", R.string.firewall_family_ipv6),
        ),
        groupRes = R.string.firewall_general,
    ),
    UciFieldSpec(
        "match", R.string.firewall_ipsets_match, UciFieldType.MULTI_SELECT, uciList = true,
        options = FwIpsetMatchOptions, required = true, groupRes = R.string.firewall_general,
    ),
    UciFieldSpec(
        "entry", R.string.firewall_ipsets_entry, UciFieldType.DYNAMIC_LIST,
        groupRes = R.string.firewall_general,
    ),
    UciFieldSpec(
        "maxelem", R.string.firewall_ipsets_maxelem, UciFieldType.TEXT,
        pattern = "^[0-9]{1,5}$", groupRes = R.string.firewall_general,
    ),
)

private val FwEnabledSpec = UciFieldSpec(
    "enabled", R.string.firewall_field_enabled, UciFieldType.SWITCH, default = "1",
)

private val FwDefaultsSchema = listOf(
    UciFieldSpec(
        "input", R.string.firewall_field_input, UciFieldType.SELECT,
        options = FwPolicyOptions, groupRes = R.string.firewall_general,
    ),
    UciFieldSpec(
        "output", R.string.firewall_field_output, UciFieldType.SELECT,
        options = FwPolicyOptions, groupRes = R.string.firewall_general,
    ),
    UciFieldSpec(
        "forward", R.string.firewall_field_forward, UciFieldType.SELECT,
        options = FwPolicyOptions, groupRes = R.string.firewall_general,
    ),
    UciFieldSpec(
        "synflood_protect", R.string.firewall_field_synflood, UciFieldType.SWITCH,
        default = "1", groupRes = R.string.firewall_general,
    ),
    UciFieldSpec(
        "drop_invalid", R.string.firewall_field_drop_invalid, UciFieldType.SWITCH,
        groupRes = R.string.firewall_general,
    ),
    // immortalwrt 特性（fw4）：FullCone NAT / 流卸载；设备不支持时 uci 选项被 fw4 忽略，无害
    UciFieldSpec(
        "fullcone", R.string.firewall_field_fullcone, UciFieldType.SWITCH,
        groupRes = R.string.firewall_general,
    ),
    UciFieldSpec(
        "fullcone6", R.string.firewall_field_fullcone6, UciFieldType.SWITCH,
        groupRes = R.string.firewall_general,
    ),
    UciFieldSpec(
        "flow_offloading", R.string.firewall_field_offloading, UciFieldType.SWITCH,
        groupRes = R.string.firewall_general,
    ),
    UciFieldSpec(
        "flow_offloading_hw", R.string.firewall_field_offloading_hw, UciFieldType.SWITCH,
        dependsKey = "flow_offloading", dependsValues = listOf("1"),
        groupRes = R.string.firewall_general,
    ),
)

private val FwZoneSchema = listOf(
    // 已有 zone 改名会留 forwarding/rule 引用悬空（原版走 renameZone 联动）——已有段名称只读
    UciFieldSpec(
        "name", R.string.firewall_field_name, UciFieldType.TEXT, required = true, readOnly = true,
        pattern = "^[a-zA-Z_][a-zA-Z0-9_]+$", patternErrorRes = R.string.firewall_invalid_name,
        groupRes = R.string.firewall_general,
    ),
    UciFieldSpec(
        "network", R.string.firewall_field_network, UciFieldType.MULTI_SELECT, uciList = true,
        candidates = UciCandidates.INTERFACES, groupRes = R.string.firewall_general,
    ),
    UciFieldSpec(
        "input", R.string.firewall_field_input, UciFieldType.SELECT, options = FwPolicyOptions,
        default = "DROP", groupRes = R.string.firewall_general,
    ),
    UciFieldSpec(
        "output", R.string.firewall_field_output, UciFieldType.SELECT, options = FwPolicyOptions,
        default = "ACCEPT", groupRes = R.string.firewall_general,
    ),
    UciFieldSpec(
        "forward", R.string.firewall_field_forward, UciFieldType.SELECT, options = FwPolicyOptions,
        default = "REJECT", groupRes = R.string.firewall_general,
    ),
    UciFieldSpec("masq", R.string.firewall_field_masq, UciFieldType.SWITCH, groupRes = R.string.firewall_general),
    UciFieldSpec("masq6", R.string.firewall_field_masq6, UciFieldType.SWITCH, groupRes = R.string.firewall_general),
    UciFieldSpec("mtu_fix", R.string.firewall_field_mtu, UciFieldType.SWITCH, groupRes = R.string.firewall_general),
    // 高级组（对齐 LuCI advanced tab）
    UciFieldSpec(
        "family", R.string.firewall_field_family, UciFieldType.SELECT,
        options = FwFamilyOptions, groupRes = R.string.firewall_group_advanced,
    ),
    UciFieldSpec(
        "device", R.string.firewall_field_device, UciFieldType.MULTI_SELECT, uciList = true,
        candidates = UciCandidates.DEVICES, groupRes = R.string.firewall_group_advanced,
    ),
    UciFieldSpec(
        "subnet", R.string.firewall_field_subnet, UciFieldType.DYNAMIC_LIST,
        groupRes = R.string.firewall_group_advanced,
    ),
    UciFieldSpec(
        "masq_src", R.string.firewall_field_masq_src, UciFieldType.DYNAMIC_LIST,
        groupRes = R.string.firewall_group_advanced,
    ),
    UciFieldSpec(
        "masq_dest", R.string.firewall_field_masq_dest, UciFieldType.DYNAMIC_LIST,
        groupRes = R.string.firewall_group_advanced,
    ),
    // 连接跟踪组（对齐 LuCI conntrack tab）
    UciFieldSpec(
        "masq_allow_invalid", R.string.firewall_field_masq_allow_invalid, UciFieldType.SWITCH,
        groupRes = R.string.firewall_group_conntrack,
    ),
    UciFieldSpec(
        "auto_helper", R.string.firewall_field_auto_helper, UciFieldType.SWITCH, default = "1",
        groupRes = R.string.firewall_group_conntrack,
    ),
    UciFieldSpec(
        "helper", R.string.firewall_field_helper, UciFieldType.MULTI_SELECT, uciList = true,
        candidates = UciCandidates.HELPERS, dependsKey = "auto_helper", dependsValues = listOf("0"),
        groupRes = R.string.firewall_group_conntrack,
    ),
)

private val FwForwardingSchema = listOf(
    UciFieldSpec("src", R.string.firewall_field_src, UciFieldType.SELECT, required = true, candidates = UciCandidates.ZONES),
    UciFieldSpec("dest", R.string.firewall_field_dest, UciFieldType.SELECT, required = true, candidates = UciCandidates.ZONES),
)

private val FwRedirectSchema = listOf(
    UciFieldSpec("name", R.string.firewall_field_name, UciFieldType.TEXT, groupRes = R.string.firewall_general),
    FwEnabledSpec.copy(groupRes = R.string.firewall_general),
    UciFieldSpec(
        "proto", R.string.firewall_field_proto, UciFieldType.MULTI_SELECT, uciList = true,
        options = FwProtoOptions, default = "tcp udp", allowCustom = true,
        groupRes = R.string.firewall_general,
    ),
    UciFieldSpec(
        "src", R.string.firewall_field_src, UciFieldType.SELECT, candidates = UciCandidates.ZONES,
        default = "wan", groupRes = R.string.firewall_general,
    ),
    UciFieldSpec(
        "src_dport", R.string.firewall_field_ext_port, UciFieldType.TEXT, required = true,
        pattern = FW_PORT_PATTERN, patternErrorRes = R.string.firewall_invalid_port,
        dependsKey = "proto", dependsValues = listOf("tcp", "udp"),
        groupRes = R.string.firewall_general,
    ),
    UciFieldSpec(
        "dest", R.string.firewall_field_dest, UciFieldType.SELECT, candidates = UciCandidates.ZONES,
        groupRes = R.string.firewall_general,
    ),
    UciFieldSpec(
        "dest_ip", R.string.firewall_field_dest_ip, UciFieldType.TEXT, required = true,
        pattern = FW_IP_PATTERN, patternErrorRes = R.string.firewall_invalid_ip,
        groupRes = R.string.firewall_general,
    ),
    UciFieldSpec(
        "dest_port", R.string.firewall_field_dest_port, UciFieldType.TEXT,
        pattern = FW_PORT_PATTERN, patternErrorRes = R.string.firewall_invalid_port,
        dependsKey = "proto", dependsValues = listOf("tcp", "udp"),
        groupRes = R.string.firewall_general,
    ),
    UciFieldSpec(
        "reflection", R.string.firewall_field_reflection, UciFieldType.SWITCH, default = "1",
        groupRes = R.string.firewall_general,
    ),
    // 高级组
    UciFieldSpec(
        "family", R.string.firewall_field_family, UciFieldType.SELECT,
        options = FwRedirectFamilyOptions, groupRes = R.string.firewall_group_advanced,
    ),
    UciFieldSpec(
        "src_mac", R.string.firewall_field_src_mac, UciFieldType.DYNAMIC_LIST,
        groupRes = R.string.firewall_group_advanced,
    ),
    UciFieldSpec(
        "src_ip", R.string.firewall_field_src_ip, UciFieldType.DYNAMIC_LIST,
        pattern = FW_IP_PATTERN, patternErrorRes = R.string.firewall_invalid_ip,
        groupRes = R.string.firewall_group_advanced,
    ),
    UciFieldSpec(
        "src_port", R.string.firewall_field_src_port, UciFieldType.TEXT,
        pattern = FW_PORT_PATTERN, patternErrorRes = R.string.firewall_invalid_port,
        dependsKey = "proto", dependsValues = listOf("tcp", "udp"),
        groupRes = R.string.firewall_group_advanced,
    ),
    UciFieldSpec(
        "src_dip", R.string.firewall_field_src_dip, UciFieldType.TEXT,
        pattern = FW_IP_PATTERN, patternErrorRes = R.string.firewall_invalid_ip,
        groupRes = R.string.firewall_group_advanced,
    ),
    UciFieldSpec(
        "ipset", R.string.firewall_field_ipset, UciFieldType.TEXT, candidates = UciCandidates.IPSETS,
        groupRes = R.string.firewall_group_advanced,
    ),
    UciFieldSpec(
        "helper", R.string.firewall_field_helper, UciFieldType.TEXT, candidates = UciCandidates.HELPERS,
        groupRes = R.string.firewall_group_advanced,
    ),
    UciFieldSpec(
        "reflection_src", R.string.firewall_field_reflection_src, UciFieldType.SELECT,
        options = listOf(
            UciOption("", R.string.firewall_reflection_src_internal),
            UciOption("external", R.string.firewall_reflection_src_external),
        ),
        dependsKey = "reflection", dependsValues = listOf("1"),
        groupRes = R.string.firewall_group_advanced,
    ),
    UciFieldSpec(
        "reflection_zone", R.string.firewall_field_reflection_zone, UciFieldType.MULTI_SELECT,
        uciList = true, candidates = UciCandidates.ZONES,
        dependsKey = "reflection", dependsValues = listOf("1"),
        groupRes = R.string.firewall_group_advanced,
    ),
    UciFieldSpec(
        "log", R.string.firewall_field_log, UciFieldType.SWITCH, groupRes = R.string.firewall_group_advanced,
    ),
    UciFieldSpec(
        "log_limit", R.string.firewall_field_log_limit, UciFieldType.TEXT,
        dependsKey = "log", dependsValues = listOf("1"), groupRes = R.string.firewall_group_advanced,
    ),
)

private val FwNatSchema = listOf(
    UciFieldSpec("name", R.string.firewall_field_name, UciFieldType.TEXT, groupRes = R.string.firewall_general),
    FwEnabledSpec.copy(groupRes = R.string.firewall_general),
    UciFieldSpec(
        "proto", R.string.firewall_field_proto, UciFieldType.MULTI_SELECT, uciList = true,
        options = FwProtoOptions, default = "tcp udp", allowCustom = true,
        groupRes = R.string.firewall_general,
    ),
    UciFieldSpec(
        "src", R.string.firewall_field_src, UciFieldType.SELECT, candidates = UciCandidates.ZONES,
        groupRes = R.string.firewall_general,
    ),
    UciFieldSpec(
        "dest", R.string.firewall_field_dest, UciFieldType.SELECT, candidates = UciCandidates.ZONES,
        groupRes = R.string.firewall_general,
    ),
    UciFieldSpec(
        "target", R.string.firewall_field_target, UciFieldType.SELECT, options = FwNatTargetOptions,
        default = "MASQUERADE", groupRes = R.string.firewall_general,
    ),
    UciFieldSpec(
        "snat_ip", R.string.firewall_field_snat_ip, UciFieldType.TEXT,
        pattern = FW_IP_PATTERN, patternErrorRes = R.string.firewall_invalid_ip,
        dependsKey = "target", dependsValues = listOf("SNAT"),
        groupRes = R.string.firewall_general,
    ),
    UciFieldSpec(
        "snat_port", R.string.firewall_field_snat_port, UciFieldType.TEXT,
        pattern = FW_PORT_PATTERN, patternErrorRes = R.string.firewall_invalid_port,
        dependsKey = "target", dependsValues = listOf("SNAT"),
        groupRes = R.string.firewall_general,
    ),
    UciFieldSpec(
        "src_ip", R.string.firewall_field_src_ip, UciFieldType.DYNAMIC_LIST,
        pattern = FW_IP_PATTERN, patternErrorRes = R.string.firewall_invalid_ip,
        groupRes = R.string.firewall_general,
    ),
    UciFieldSpec(
        "dest_ip", R.string.firewall_field_dest_ip, UciFieldType.DYNAMIC_LIST,
        pattern = FW_IP_PATTERN, patternErrorRes = R.string.firewall_invalid_ip,
        groupRes = R.string.firewall_general,
    ),
    // 高级组
    UciFieldSpec(
        "family", R.string.firewall_field_family, UciFieldType.SELECT,
        options = FwFamilyOptions, groupRes = R.string.firewall_group_advanced,
    ),
    UciFieldSpec(
        "ipset", R.string.firewall_field_ipset, UciFieldType.TEXT, candidates = UciCandidates.IPSETS,
        groupRes = R.string.firewall_group_advanced,
    ),
    UciFieldSpec(
        "log", R.string.firewall_field_log, UciFieldType.SWITCH, groupRes = R.string.firewall_group_advanced,
    ),
    UciFieldSpec(
        "log_limit", R.string.firewall_field_log_limit, UciFieldType.TEXT,
        dependsKey = "log", dependsValues = listOf("1"), groupRes = R.string.firewall_group_advanced,
    ),
) + fwTimedFields()

private val FwRuleSchema = listOf(
    UciFieldSpec("name", R.string.firewall_field_name, UciFieldType.TEXT, groupRes = R.string.firewall_general),
    FwEnabledSpec.copy(groupRes = R.string.firewall_general),
    UciFieldSpec(
        "proto", R.string.firewall_field_proto, UciFieldType.MULTI_SELECT, uciList = true,
        options = FwProtoOptions, default = "tcp udp", allowCustom = true,
        groupRes = R.string.firewall_general,
    ),
    UciFieldSpec(
        "icmp_type", R.string.firewall_field_icmp_type, UciFieldType.MULTI_SELECT, uciList = true,
        options = FwIcmpTypes, dependsKey = "proto", dependsValues = listOf("icmp", "icmpv6"),
        groupRes = R.string.firewall_general,
    ),
    UciFieldSpec(
        "src", R.string.firewall_field_src, UciFieldType.SELECT, candidates = UciCandidates.ZONES,
        groupRes = R.string.firewall_general,
    ),
    UciFieldSpec(
        "src_ip", R.string.firewall_field_src_ip, UciFieldType.DYNAMIC_LIST,
        pattern = FW_IP_PATTERN, patternErrorRes = R.string.firewall_invalid_ip,
        groupRes = R.string.firewall_general,
    ),
    UciFieldSpec(
        "src_port", R.string.firewall_field_src_port, UciFieldType.TEXT,
        pattern = FW_PORT_PATTERN, patternErrorRes = R.string.firewall_invalid_port,
        dependsKey = "proto", dependsValues = listOf("tcp", "udp"),
        groupRes = R.string.firewall_general,
    ),
    UciFieldSpec(
        "dest", R.string.firewall_field_dest, UciFieldType.SELECT, candidates = UciCandidates.ZONES,
        groupRes = R.string.firewall_general,
    ),
    UciFieldSpec(
        "dest_ip", R.string.firewall_field_dest_ip, UciFieldType.DYNAMIC_LIST,
        pattern = FW_IP_PATTERN, patternErrorRes = R.string.firewall_invalid_ip,
        groupRes = R.string.firewall_general,
    ),
    UciFieldSpec(
        "dest_port", R.string.firewall_field_dest_port, UciFieldType.TEXT,
        pattern = FW_PORT_PATTERN, patternErrorRes = R.string.firewall_invalid_port,
        dependsKey = "proto", dependsValues = listOf("tcp", "udp"),
        groupRes = R.string.firewall_general,
    ),
    UciFieldSpec(
        "target", R.string.firewall_field_target, UciFieldType.SELECT, options = FwTargetOptions,
        default = "ACCEPT", groupRes = R.string.firewall_general,
    ),
    UciFieldSpec(
        "set_helper", R.string.firewall_field_set_helper, UciFieldType.TEXT,
        candidates = UciCandidates.HELPERS, dependsKey = "target", dependsValues = listOf("HELPER"),
        groupRes = R.string.firewall_general,
    ),
    UciFieldSpec(
        "set_mark", R.string.firewall_field_set_mark, UciFieldType.TEXT,
        dependsKey = "target", dependsValues = listOf("MARK"), groupRes = R.string.firewall_general,
    ),
    UciFieldSpec(
        "set_dscp", R.string.firewall_field_set_dscp, UciFieldType.TEXT,
        dependsKey = "target", dependsValues = listOf("DSCP"), groupRes = R.string.firewall_general,
    ),
    // 高级组
    UciFieldSpec(
        "family", R.string.firewall_field_family, UciFieldType.SELECT,
        options = FwFamilyOptions, groupRes = R.string.firewall_group_advanced,
    ),
    UciFieldSpec(
        "direction", R.string.firewall_field_direction, UciFieldType.SELECT,
        options = listOf(
            UciOption("", R.string.firewall_direction_unspecified),
            UciOption("in", R.string.firewall_direction_in),
            UciOption("out", R.string.firewall_direction_out),
        ),
        groupRes = R.string.firewall_group_advanced,
    ),
    UciFieldSpec(
        "device", R.string.firewall_field_device, UciFieldType.DEVICE_SELECT,
        candidates = UciCandidates.DEVICES, dependsKey = "direction", dependsValues = listOf("in", "out"),
        groupRes = R.string.firewall_group_advanced,
    ),
    UciFieldSpec(
        "ipset", R.string.firewall_field_ipset, UciFieldType.TEXT, candidates = UciCandidates.IPSETS,
        groupRes = R.string.firewall_group_advanced,
    ),
    UciFieldSpec(
        "src_mac", R.string.firewall_field_src_mac, UciFieldType.DYNAMIC_LIST,
        groupRes = R.string.firewall_group_advanced,
    ),
    UciFieldSpec(
        "helper", R.string.firewall_field_helper, UciFieldType.TEXT, candidates = UciCandidates.HELPERS,
        groupRes = R.string.firewall_group_advanced,
    ),
    UciFieldSpec(
        "limit", R.string.firewall_field_limit, UciFieldType.TEXT,
        pattern = "^!?[0-9]+/[smhd]$", groupRes = R.string.firewall_group_advanced,
    ),
    UciFieldSpec(
        "limit_burst", R.string.firewall_field_limit_burst, UciFieldType.TEXT,
        pattern = "^[0-9]*$", groupRes = R.string.firewall_group_advanced,
    ),
    UciFieldSpec(
        "log", R.string.firewall_field_log, UciFieldType.SWITCH, groupRes = R.string.firewall_group_advanced,
    ),
    UciFieldSpec(
        "log_limit", R.string.firewall_field_log_limit, UciFieldType.TEXT,
        dependsKey = "log", dependsValues = listOf("1"), groupRes = R.string.firewall_group_advanced,
    ),
) + fwTimedFields()

// CyclomaticComplexMethod：六类段列表区已抽 FirewallListContent，剩余为编辑器/弹窗三态机
// 固有复杂度（ClientScreen 先例）
@Suppress("CyclomaticComplexMethod")
@Composable
fun FirewallScreen(deviceId: String?, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: FirewallViewModel = viewModel(factory = viewModelFactory { initializer { FirewallViewModel(app) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val candidates by vm.candidates.collectAsStateWithLifecycle()
    val custom by vm.customRules.collectAsStateWithLifecycle()
    val doneEvent by vm.doneEvent.collectAsStateWithLifecycle()
    LaunchedEffect(deviceId) {
        vm.ensureLoaded(deviceId)
        vm.loadCustom()
    }
    PluginWriteErrorEffect(state.writeErrorRes) { vm.consumeWriteError() }
    PluginDoneEventEffect(doneEvent) { vm.consumeDoneEvent() }
    val context = LocalContext.current
    LaunchedEffect(custom.savedCount) {
        if (custom.savedCount > 0) {
            Toast.makeText(context, R.string.plugin_save_success, Toast.LENGTH_SHORT).show()
        }
    }
    // 编辑目标：type ∈ defaults/zone/forwarding/redirect/rule/nat/custom；editSection=null=新建
    var editType by rememberSaveable { mutableStateOf<String?>(null) }
    var editSection by rememberSaveable { mutableStateOf<String?>(null) }
    // 删除确认目标（编辑页删除键 → 确认弹窗 → remove）
    var confirmDeleteSection by rememberSaveable { mutableStateOf<String?>(null) }
    val editEntry = state.entries.firstOrNull { it.section == editSection }
    val defaults = state.entries.firstOrNull { it.type == "defaults" }
    // 进程复活边界：defaults 仅编辑（create=false 无 section 会 NPE），段已不在则回列表
    LaunchedEffect(editType, editSection, state.loading) {
        if (editType == "defaults" && editEntry == null && !state.loading) editType = null
    }
    val zones = state.entries.filter { it.type == "zone" }
    val forwardings = state.entries.filter { it.type == "forwarding" }
    val redirects = state.entries.filter { it.type == "redirect" }
    val rules = state.entries.filter { it.type == "rule" }
    val nats = state.entries.filter { it.type == "nat" }
    val ipsets = state.entries.filter { it.type == "ipset" }

    if (editType == "custom") {
        FirewallCustomEditor(
            custom = custom,
            onBack = { editType = null },
            onSave = { text ->
                vm.saveCustom(text) { ok -> if (ok) editType = null }
            },
        )
        return
    }
    if (editType != null) {
        // 编辑器定义查表（schema/段类型/标题/删除许可），firewall 全部 uci 编辑都是高危红字确认
        val def = FwEditors[editType]
        if (def == null) {
            editType = null
            return
        }
        val isCreate = editEntry == null && editType != "defaults"
        UciEditPage(
            title = stringResource(if (isCreate) def.createTitleRes else def.editTitleRes),
            specs = def.schema,
            entry = editEntry,
            candidates = candidates,
            allowDelete = def.allowDelete,
            highRisk = true,
            saving = state.saving,
            onDelete = if (editEntry != null) {
                { confirmDeleteSection = editSection }
            } else {
                null
            },
            onCancel = { editType = null },
            onSave = { values ->
                vm.submit(
                    create = isCreate,
                    sectionType = def.sectionType,
                    editSection = editSection,
                    values = values,
                ) { ok -> if (ok) editType = null }
            },
        )
        if (confirmDeleteSection != null) {
            PluginConfirmDialog(
                title = stringResource(R.string.plugin_delete),
                body = stringResource(R.string.plugin_delete_confirm),
                confirmText = stringResource(R.string.plugin_delete),
                onConfirm = {
                    val section = confirmDeleteSection
                    confirmDeleteSection = null
                    if (section != null) vm.remove(section) { ok -> if (ok) editType = null }
                },
                onDismiss = { confirmDeleteSection = null },
            )
        }
        return
    }

    PluginPage(stringResource(R.string.firewall_title), null, onBack, state.refreshing, vm::refresh) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            when {
                state.loading -> ToolStateBox(spinner = true)
                state.entries.isEmpty() && state.loadFailed ->
                    ToolErrorRetry(stringResource(R.string.common_load_failed), onRetry = vm::retry)
                else -> FirewallListContent(
                    busySection = state.busySection,
                    custom = custom,
                    defaults = defaults,
                    zones = zones,
                    forwardings = forwardings,
                    redirects = redirects,
                    rules = rules,
                    nats = nats,
                    ipsets = ipsets,
                    onToggle = { entry, on -> vm.toggle(entry, UciEditorLogic.switchPatch(FwEnabledSpec, on)) },
                    onOpen = { type, section -> editType = type; editSection = section },
                )
            }
        }
    }
}
/** 六类段编辑器定义（查表驱动，附新建/编辑双标题与删除许可） */
private data class FwEditorDef(
    val schema: List<UciFieldSpec>,
    val sectionType: String,
    val allowDelete: Boolean,
    val editTitleRes: Int,
    val createTitleRes: Int,
)

private val FwEditors = mapOf(
    "defaults" to FwEditorDef(
        FwDefaultsSchema, "defaults", allowDelete = false,
        editTitleRes = R.string.firewall_edit_defaults, createTitleRes = R.string.firewall_edit_defaults,
    ),
    "zone" to FwEditorDef(
        FwZoneSchema, "zone", allowDelete = true,
        editTitleRes = R.string.firewall_edit_zone, createTitleRes = R.string.firewall_add_zone,
    ),
    "forwarding" to FwEditorDef(
        FwForwardingSchema, "forwarding", allowDelete = true,
        editTitleRes = R.string.firewall_edit_fwd, createTitleRes = R.string.firewall_add_fwd,
    ),
    "redirect" to FwEditorDef(
        FwRedirectSchema, "redirect", allowDelete = true,
        editTitleRes = R.string.firewall_edit_redirect, createTitleRes = R.string.firewall_add_redirect,
    ),
    "rule" to FwEditorDef(
        FwRuleSchema, "rule", allowDelete = true,
        editTitleRes = R.string.firewall_edit_rule, createTitleRes = R.string.firewall_add_rule,
    ),
    "nat" to FwEditorDef(
        FwNatSchema, "nat", allowDelete = true,
        editTitleRes = R.string.firewall_edit_nat, createTitleRes = R.string.firewall_add_nat,
    ),
    "ipset" to FwEditorDef(
        FwIpsetSchema, "ipset", allowDelete = true,
        editTitleRes = R.string.firewall_ipsets_edit, createTitleRes = R.string.firewall_ipsets_add,
    ),
)

/** 列表区：常规设置卡 + 六类段 + IP Sets + 自定义规则卡（编辑器入口经 onOpen(type, section)） */
@Composable
private fun FirewallListContent(
    busySection: String?,
    custom: FirewallViewModel.CustomRulesUi,
    defaults: UciEntry?,
    zones: List<UciEntry>,
    forwardings: List<UciEntry>,
    redirects: List<UciEntry>,
    rules: List<UciEntry>,
    nats: List<UciEntry>,
    ipsets: List<UciEntry>,
    onToggle: (UciEntry, Boolean) -> Unit,
    onOpen: (String, String?) -> Unit,
) {
    FwDefaultsCard(defaults) { onOpen("defaults", defaults?.section) }
    FwSection(
        stringResource(R.string.firewall_zones),
        stringResource(R.string.firewall_add_zone),
        zones.isNotEmpty(),
        emptyText = stringResource(R.string.firewall_no_zones),
        onAdd = { onOpen("zone", null) },
    ) { zones.forEach { FwZoneRow(it) { onOpen("zone", it.section) } } }
    FwSection(
        stringResource(R.string.firewall_forwardings),
        stringResource(R.string.firewall_add_fwd),
        forwardings.isNotEmpty(),
        emptyText = stringResource(R.string.firewall_no_forwardings),
        onAdd = { onOpen("forwarding", null) },
    ) { forwardings.forEach { FwForwardRow(it) { onOpen("forwarding", it.section) } } }
    FwSection(
        stringResource(R.string.firewall_redirects),
        stringResource(R.string.firewall_add_redirect),
        redirects.isNotEmpty(),
        emptyText = stringResource(R.string.firewall_no_redirects),
        onAdd = { onOpen("redirect", null) },
    ) {
        redirects.forEachIndexed { index, entry ->
            FwToggleRow(
                title = entry.first("name")?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.firewall_unnamed),
                summary = fwRedirectSummary(entry),
                enabled = entry.first("enabled") != "0",
                busy = busySection == entry.section,
                onToggle = { on -> onToggle(entry, on) },
                onEdit = { onOpen("redirect", entry.section) },
            )
            if (index != redirects.lastIndex) HorizontalDivider()
        }
    }
    FwSection(
        stringResource(R.string.firewall_rules),
        stringResource(R.string.firewall_add_rule),
        rules.isNotEmpty(),
        emptyText = stringResource(R.string.firewall_no_rules),
        onAdd = { onOpen("rule", null) },
    ) {
        rules.forEachIndexed { index, entry ->
            FwToggleRow(
                title = entry.first("name")?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.firewall_unnamed),
                summary = fwRuleSummary(entry),
                enabled = entry.first("enabled") != "0",
                busy = busySection == entry.section,
                onToggle = { on -> onToggle(entry, on) },
                onEdit = { onOpen("rule", entry.section) },
            )
            if (index != rules.lastIndex) HorizontalDivider()
        }
    }
    FwSection(
        stringResource(R.string.firewall_nats),
        stringResource(R.string.firewall_add_nat),
        nats.isNotEmpty(),
        emptyText = stringResource(R.string.firewall_no_nats),
        onAdd = { onOpen("nat", null) },
    ) {
        nats.forEachIndexed { index, entry ->
            FwToggleRow(
                title = entry.first("name")?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.firewall_unnamed),
                summary = fwNatSummary(entry),
                enabled = entry.first("enabled") != "0",
                busy = busySection == entry.section,
                onToggle = { on -> onToggle(entry, on) },
                onEdit = { onOpen("nat", entry.section) },
            )
            if (index != nats.lastIndex) HorizontalDivider()
        }
    }
    FwSection(
        stringResource(R.string.firewall_ipsets),
        stringResource(R.string.firewall_ipsets_add),
        ipsets.isNotEmpty(),
        emptyText = stringResource(R.string.firewall_no_ipsets),
        onAdd = { onOpen("ipset", null) },
    ) {
        ipsets.forEachIndexed { index, entry ->
            FwIpsetRow(entry) { onOpen("ipset", entry.section) }
            if (index != ipsets.lastIndex) HorizontalDivider()
        }
    }
    // 自定义规则卡（file 通道 + restart）
    Card(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(
                stringResource(R.string.firewall_custom_rules),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(R.string.firewall_custom_hint),
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val lineCount = custom.text.split('\n').count { it.isNotBlank() }
                Text(
                    // 生成键 firewall_lines 是纯名词「行」无占位符，数量前置拼接
                    if (custom.text.isNotBlank()) {
                        "$lineCount " + stringResource(R.string.firewall_lines)
                    } else {
                        stringResource(R.string.firewall_empty_rules)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { onOpen("custom", null) }) {
                    Text(stringResource(R.string.firewall_edit_custom))
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
}

/** 区块：sechead + 添加动作 + 卡片内容（空态插件化） */
@Composable
private fun FwSection(
    title: String,
    addAction: String,
    hasItems: Boolean,
    emptyText: String,
    onAdd: () -> Unit,
    content: @Composable () -> Unit,
) {
    PluginAddHead(title, addAction, onAdd)
    if (hasItems) {
        Card(Modifier.fillMaxWidth()) {
            Column { content() }
        }
    } else {
        PluginEmptyHint(emptyText)
    }
}

@Composable
private fun FwDefaultsCard(defaults: UciEntry?, onEdit: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(
            Modifier.fillMaxWidth().clickable(enabled = defaults != null, onClick = onEdit).padding(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.firewall_general),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(R.string.plugin_edit),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (defaults != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FwKvRow(stringResource(R.string.firewall_field_input), defaults?.first("input") ?: "DROP")
            FwKvRow(stringResource(R.string.firewall_field_output), defaults?.first("output") ?: "ACCEPT")
            FwKvRow(stringResource(R.string.firewall_field_forward), defaults?.first("forward") ?: "REJECT")
            val synflood = defaults?.first("synflood_protect") == "1"
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.firewall_field_synflood),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ToolBadge(
                    stringResource(if (synflood) R.string.plugin_enabled else R.string.plugin_disabled),
                    if (synflood) ToolBadgeKind.Positive else ToolBadgeKind.Neutral,
                )
            }
        }
    }
}

@Composable
private fun FwKvRow(key: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(key, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun FwZoneDot(name: String?) {
    val color = FirewallColors.zoneColor(name)
    Box(
        Modifier
            .size(11.dp)
            .clip(CircleShape)
            .background(Color(android.graphics.Color.parseColor(color))),
    )
}

@Composable
private fun FwZoneRow(zone: UciEntry, onEdit: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FwZoneDot(zone.first("name"))
        Column(Modifier.weight(1f)) {
            Text(
                zone.first("name") ?: zone.section,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "in:${zone.first("input") ?: "?"} · out:${zone.first("output") ?: "?"} · fwd:${zone.first("forward") ?: "?"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (zone.first("masq") == "1") ToolBadge("MASQ", ToolBadgeKind.Info)
        if (zone.first("mtu_fix") == "1") ToolBadge("MSS", ToolBadgeKind.Neutral)
    }
}

@Composable
private fun FwForwardRow(fwd: UciEntry, onEdit: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FwZoneDot(fwd.first("src"))
        Text(fwd.first("src") ?: "?", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text("→", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FwZoneDot(fwd.first("dest"))
        Text(fwd.first("dest") ?: "?", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun FwToggleRow(
    title: String,
    summary: String,
    enabled: Boolean,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.weight(1f).clickable(onClick = onEdit),
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PluginBusySwitch(enabled, busy, onToggle)
    }
}

@Composable
private fun FwIpsetRow(ipset: UciEntry, onEdit: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                ipset.first("name") ?: ipset.section,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                buildString {
                    append(ipset.first("family") ?: "ipv4")
                    ipset.options["match"]?.takeIf { it.isNotEmpty() }?.let {
                        append(" · match: ").append(it.joinToString(","))
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ipset.options["entry"]?.takeIf { it.isNotEmpty() }?.let {
            ToolBadge("${it.size}", ToolBadgeKind.Neutral)
        }
    }
}

// ── 行摘要（zone 语义标签走 string 资源） ──

@Composable
private fun fwProtoText(entry: UciEntry): String {
    val anyLabel = stringResource(R.string.firewall_opt_proto_any)
    val labels = (entry.options["proto"] ?: emptyList())
        .filter { it.isNotBlank() && it !in setOf("*", "any", "all") }
        .map { PROTO_LABELS[it] ?: it.uppercase() }
    return if (labels.isEmpty()) anyLabel else labels.joinToString("/")
}

private val PROTO_LABELS = mapOf(
    "tcp" to "TCP",
    "udp" to "UDP",
    "icmp" to "ICMP",
    "ipv6-icmp" to "IPv6-ICMP",
    "icmpv6" to "IPv6-ICMP",
)

@Composable
private fun fwZoneLabel(zone: String?): String = when {
    zone == null || zone.isEmpty() -> stringResource(R.string.firewall_zone_this_device)
    zone == "*" -> stringResource(R.string.firewall_zone_any)
    else -> zone
}

private fun ipList(entry: UciEntry, key: String): String? =
    entry.options[key]?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.joinToString(" / ")

@Composable
private fun fwRedirectSummary(r: UciEntry): String {
    val ext = r.first("src_dport") ?: ""
    val ip = r.first("dest_ip") ?: stringResource(R.string.firewall_zone_this_device)
    val port = r.first("dest_port") ?: r.first("src_dport")
    val fwd = if (port != null) "$ip:$port" else ip
    return "${fwProtoText(r)} · ${fwZoneLabel(r.first("src"))} · $ext → ${fwZoneLabel(r.first("dest"))} · $fwd"
}

@Composable
private fun fwRuleSummary(r: UciEntry): String {
    val action = r.first("target")?.let { target ->
        FwTargetOptions.firstOrNull { it.value == target }?.let { stringResource(it.labelRes!!) } ?: target
    } ?: stringResource(R.string.firewall_opt_accept)
    val from = buildString {
        append(fwZoneLabel(r.first("src")))
        ipList(r, "src_ip")?.let { append(' ').append(it) }
        r.first("src_port")?.let { append(':').append(it) }
    }
    val to = buildString {
        append(fwZoneLabel(r.first("dest")))
        ipList(r, "dest_ip")?.let { append(' ').append(it) }
        r.first("dest_port")?.let { append(':').append(it) }
    }
    return "${fwProtoText(r)} · $from → $to · $action"
}

@Composable
private fun fwNatSummary(r: UciEntry): String {
    val target = r.first("target") ?: "MASQUERADE"
    val action = when (target) {
        "SNAT" -> buildString {
            append("SNAT ").append(r.first("snat_ip") ?: "")
            r.first("snat_port")?.let { append(':').append(it) }
        }.trim()
        "ACCEPT" -> stringResource(R.string.firewall_opt_no_rewrite)
        else -> stringResource(R.string.firewall_opt_masquerade)
    }
    val from = buildString {
        append(fwZoneLabel(r.first("src")))
        ipList(r, "src_ip")?.let { append(' ').append(it) }
    }
    val to = buildString {
        append(fwZoneLabel(r.first("dest")))
        ipList(r, "dest_ip")?.let { append(' ').append(it) }
    }
    return "${fwProtoText(r)} · $from → $to · $action"
}

// ── 自定义规则文本编辑页（唯一非 schema 表单；writeFile + restart） ──

@Composable
private fun FirewallCustomEditor(
    custom: FirewallViewModel.CustomRulesUi,
    onBack: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(custom.text) }
    ToolPage(title = stringResource(R.string.firewall_custom_rules), onBack = onBack) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Text(
                stringResource(R.string.firewall_custom_hint),
                Modifier.padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.firewall_custom_placeholder)) },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(onClick = onBack, enabled = !custom.saving, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.plugin_cancel))
                }
                Button(onClick = { onSave(text) }, enabled = !custom.saving, modifier = Modifier.weight(2f)) {
                    if (custom.saving) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.firewall_save_apply))
                    }
                }
            }
        }
    }
}
