@file:Suppress("TooManyFunctions")

package dev.wrtctrl.ui.screen

import android.app.Application
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.wrtctrl.R
import dev.wrtctrl.ui.component.InfoRow
import dev.wrtctrl.viewmodel.plugin.ArpbindViewModel
import dev.wrtctrl.viewmodel.plugin.AutorebootViewModel
import dev.wrtctrl.viewmodel.plugin.CifsViewModel
import dev.wrtctrl.viewmodel.plugin.SambaViewModel
import dev.wrtctrl.viewmodel.plugin.UciCandidates
import dev.wrtctrl.viewmodel.plugin.UciEditorLogic
import dev.wrtctrl.viewmodel.plugin.UciEntry
import dev.wrtctrl.viewmodel.plugin.UciFieldSpec
import dev.wrtctrl.viewmodel.plugin.UciFieldType
import dev.wrtctrl.viewmodel.plugin.UciOption
import dev.wrtctrl.viewmodel.plugin.UciPluginViewModel
import dev.wrtctrl.viewmodel.plugin.UsbPrinterViewModel
import dev.wrtctrl.viewmodel.plugin.WolViewModel
import java.util.Locale

/**
 * 简单插件六屏：arpbind / autoreboot / cifs / samba4 / usb-printer / wolultra。
 * 前四屏走 SimplePluginScreen 共享骨架（工厂 lambda 注入 VM，避免泛型 viewModel 限制）；
 * samba4（全局+共享两段）/ wolultra（行级唤醒）/ usb-printer（发现区）自有实现。
 * schema 全部与 LuCI 行为一致字段表（，含 invert/yes-no/depends）。
 */

/** 编辑器打开键：新建（区分于 section 名）——共享定义见 PluginCommon.PLUGIN_CREATE */

// ── IP/MAC 绑定 ──

/** enabled 反逻辑开关（列表行与编辑表单共用同一映射，单点定义） */
private val ArpbindEnabledSpec = UciFieldSpec(
    "enabled", R.string.arpbind_enable, UciFieldType.SWITCH,
    default = "0", onValue = "0", offValue = "1",
)

private val ArpbindSchema = listOf(
    ArpbindEnabledSpec,
    UciFieldSpec(
        "ipaddr", R.string.arpbind_ipaddr, UciFieldType.TEXT, required = true,
        candidates = UciCandidates.HOSTHINTS_IP, placeholder = "192.168.1.100",
        pattern = "^[a-zA-Z0-9.:]+$",
    ),
    UciFieldSpec(
        "macaddr", R.string.arpbind_macaddr, UciFieldType.TEXT, required = true,
        candidates = UciCandidates.HOSTHINTS_MAC, placeholder = "00:11:22:33:44:55",
        pattern = "^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$",
    ),
    UciFieldSpec("ifname", R.string.arpbind_ifname, UciFieldType.DEVICE_SELECT, required = true),
)

@Composable
fun ArpbindScreen(deviceId: String?, onBack: () -> Unit) {
    SimplePluginScreen(
        deviceId = deviceId,
        onBack = onBack,
        titleRes = R.string.arpbind_title,
        subtitleRes = R.string.desc_arpbind,
        sectionTitleRes = R.string.arpbind_section,
        addActionRes = R.string.arpbind_add_rule,
        emptyRes = R.string.arpbind_empty,
        sectionType = "arpbind",
        schema = ArpbindSchema,
        createTitleRes = R.string.arpbind_add_rule,
        editTitleRes = R.string.arpbind_edit_rule,
        toggleSpec = ArpbindEnabledSpec,
        createVm = ::ArpbindViewModel,
        vmClass = ArpbindViewModel::class.java,
        row = { entry, busy, onToggle, onEdit -> ArpbindRow(entry, busy, onToggle, onEdit) },
    )
}

@Composable
private fun ArpbindRow(entry: UciEntry, busy: Boolean, onToggle: ((Boolean) -> Unit)?, onEdit: () -> Unit) {
    // 反逻辑：enabled='0'/缺省 = 启用（与 LuCI isEnabled 语义一致）
    val enabled = entry.first("enabled").let { it == null || it.isEmpty() || it == "0" }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                entry.first("ipaddr") ?: "-",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                buildString {
                    append(entry.first("macaddr") ?: "-")
                    entry.first("ifname")?.let { append(" · ").append(it) }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onToggle != null) {
            PluginBusySwitch(enabled, busy, onToggle)
        }
    }
}

// ── 定时重启 ──

private fun autorebootWeekOptions() = listOf(
    UciOption("*", R.string.autoreboot_every_day),
    UciOption("1", R.string.autoreboot_mon),
    UciOption("2", R.string.autoreboot_tue),
    UciOption("3", R.string.autoreboot_wed),
    UciOption("4", R.string.autoreboot_thu),
    UciOption("5", R.string.autoreboot_fri),
    UciOption("6", R.string.autoreboot_sat),
    UciOption("0", R.string.autoreboot_sun),
)

private fun autorebootMonthOptions(): List<UciOption> =
    listOf(UciOption("*", R.string.autoreboot_every_month)) +
        (1..12).map { UciOption(it.toString(), label = it.toString()) }

private val AutorebootEnabledSpec = UciFieldSpec(
    "enabled", R.string.autoreboot_enable, UciFieldType.SWITCH, default = "0",
)

/**
 * crontab 复合表达式（对齐 LuCI autoreboot validate）：单值 / 范围 / 步进 / 列表。
 * pattern 构造器与单测在 UciEditorLogic（数字段必须显式枚举，[0-23] 是字符类不是范围）。
 */
private val AutorebootSchema = listOf(
    AutorebootEnabledSpec,
    UciFieldSpec(
        "week", R.string.autoreboot_week, UciFieldType.TEXT, default = "*", required = true,
        options = autorebootWeekOptions(),
        pattern = UciEditorLogic.cronListPattern("[0-6](?:-[0-6])?"),
    ),
    UciFieldSpec(
        "hour", R.string.autoreboot_hour, UciFieldType.TEXT, default = "2", required = true,
        pattern = UciEditorLogic.cronListPattern("(?:1?[0-9]|2[0-3])(?:-(?:1?[0-9]|2[0-3]))?"),
    ),
    UciFieldSpec(
        "minute", R.string.autoreboot_minute, UciFieldType.TEXT, default = "0", required = true,
        pattern = UciEditorLogic.cronListPattern("[1-5]?[0-9](?:-[1-5]?[0-9])?"),
    ),
    UciFieldSpec(
        "month", R.string.autoreboot_month, UciFieldType.TEXT, default = "*", required = true,
        options = autorebootMonthOptions(),
        pattern = UciEditorLogic.cronListPattern("(?:[1-9]|1[0-2])(?:-(?:[1-9]|1[0-2]))?"),
    ),
    UciFieldSpec(
        "day", R.string.autoreboot_day, UciFieldType.TEXT, default = "*", required = true,
        pattern = UciEditorLogic.cronListPattern("(?:[1-9]|[12][0-9]|3[01])(?:-(?:[1-9]|[12][0-9]|3[01]))?"),
    ),
)

@Composable
fun AutorebootScreen(deviceId: String?, onBack: () -> Unit) {
    SimplePluginScreen(
        deviceId = deviceId,
        onBack = onBack,
        titleRes = R.string.autoreboot_title,
        subtitleRes = R.string.desc_autoreboot,
        sectionTitleRes = R.string.autoreboot_section,
        addActionRes = R.string.autoreboot_add_schedule,
        emptyRes = R.string.autoreboot_empty,
        sectionType = "schedule",
        schema = AutorebootSchema,
        createTitleRes = R.string.autoreboot_add_schedule,
        editTitleRes = R.string.autoreboot_edit_schedule,
        toggleSpec = AutorebootEnabledSpec,
        createVm = ::AutorebootViewModel,
        vmClass = AutorebootViewModel::class.java,
        row = { entry, busy, onToggle, onEdit -> AutorebootRow(entry, busy, onToggle, onEdit) },
    )
}

@Composable
private fun AutorebootRow(entry: UciEntry, busy: Boolean, onToggle: ((Boolean) -> Unit)?, onEdit: () -> Unit) {
    val enabled = entry.first("enabled") == "1"
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    String.format(
                        Locale.US,
                        "%02d:%02d",
                        entry.first("hour")?.toIntOrNull() ?: 0,
                        entry.first("minute")?.toIntOrNull() ?: 0,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(8.dp))
                ToolBadge(
                    stringResource(if (enabled) R.string.plugin_enabled else R.string.plugin_disabled),
                    if (enabled) ToolBadgeKind.Positive else ToolBadgeKind.Neutral,
                )
            }
            val week = entry.first("week")
            val weekLabel = autorebootWeekOptions().firstOrNull { it.value == week }
                ?.let { stringResource(it.labelRes!!) } ?: (week ?: "*")
            val sub = buildList {
                add(weekLabel)
                entry.first("month")?.takeIf { it != "*" }?.let { add(stringResource(R.string.autoreboot_month_label) + it) }
                entry.first("day")?.takeIf { it != "*" }?.let { add(stringResource(R.string.autoreboot_day_label) + it) }
            }.joinToString(" · ")
            Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onToggle != null) {
            PluginBusySwitch(enabled, busy, onToggle)
        }
    }
}

// ── CIFS 挂载 ──

private val CifsEnabledSpec = UciFieldSpec(
    "enabled", R.string.cifs_enable, UciFieldType.SWITCH, default = "0",
)

private val CifsSchema = listOf(
    UciFieldSpec("enabled", R.string.cifs_enable, UciFieldType.SWITCH, default = "0", groupRes = R.string.cifs_g_base),
    UciFieldSpec(
        "server", R.string.cifs_server, UciFieldType.TEXT, required = true,
        placeholder = "192.168.1.1", groupRes = R.string.cifs_g_base,
    ),
    UciFieldSpec(
        "remote_path", R.string.cifs_remote_path, UciFieldType.TEXT, required = true,
        placeholder = "/share", groupRes = R.string.cifs_g_base,
    ),
    UciFieldSpec(
        "local_path", R.string.cifs_local_path, UciFieldType.TEXT, required = true, default = "/mnt",
        options = listOf(UciOption("/mnt")), placeholder = "/mnt", groupRes = R.string.cifs_g_base,
    ),
    UciFieldSpec(
        "smb_version", R.string.cifs_smb_version, UciFieldType.SELECT, groupRes = R.string.cifs_g_adv,
        options = listOf(
            UciOption("", R.string.cifs_smb_default),
            UciOption("1.0", label = "SMBv1"),
            UciOption("2.0", label = "SMBv2"),
            UciOption("2.1", label = "SMBv2.1"),
            UciOption("3.0", label = "SMBv3"),
        ),
    ),
    UciFieldSpec(
        "iocharset", R.string.cifs_charset, UciFieldType.SELECT, required = true, default = "utf8",
        groupRes = R.string.cifs_g_adv,
        options = listOf(UciOption("-", R.string.cifs_charset_none), UciOption("utf8", label = "UTF-8")),
    ),
    UciFieldSpec("ro", R.string.cifs_read_only, UciFieldType.SWITCH, default = "0", groupRes = R.string.cifs_g_adv),
    UciFieldSpec("username", R.string.cifs_username, UciFieldType.TEXT, default = "guest", groupRes = R.string.cifs_g_auth),
    UciFieldSpec("password", R.string.cifs_password, UciFieldType.PASSWORD, groupRes = R.string.cifs_g_auth),
    UciFieldSpec("workgroup", R.string.cifs_workgroup, UciFieldType.TEXT, default = "WORKGROUP", groupRes = R.string.cifs_g_auth),
    UciFieldSpec(
        "options", R.string.cifs_options, UciFieldType.DYNAMIC_LIST, groupRes = R.string.cifs_g_auth,
        pattern = "^[A-Za-z0-9_=,.-]+$",
    ),
)

@Composable
fun CifsScreen(deviceId: String?, onBack: () -> Unit) {
    SimplePluginScreen(
        deviceId = deviceId,
        onBack = onBack,
        titleRes = R.string.cifs_title,
        subtitleRes = R.string.desc_cifs,
        sectionTitleRes = R.string.cifs_section,
        addActionRes = R.string.cifs_add_mount,
        emptyRes = R.string.cifs_empty,
        sectionType = "mount",
        schema = CifsSchema,
        createTitleRes = R.string.cifs_add_mount,
        editTitleRes = R.string.cifs_edit_mount,
        toggleSpec = CifsEnabledSpec,
        createVm = ::CifsViewModel,
        vmClass = CifsViewModel::class.java,
        row = { entry, busy, onToggle, onEdit -> CifsRow(entry, busy, onToggle, onEdit) },
    )
}

@Composable
private fun CifsRow(entry: UciEntry, busy: Boolean, onToggle: ((Boolean) -> Unit)?, onEdit: () -> Unit) {
    val enabled = entry.first("enabled") == "1"
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "//${entry.first("server") ?: "?"}${entry.first("remote_path").orEmpty()}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "→ ${entry.first("local_path") ?: "/mnt"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                ToolBadge(
                    stringResource(if (enabled) R.string.plugin_enabled else R.string.plugin_disabled),
                    if (enabled) ToolBadgeKind.Positive else ToolBadgeKind.Neutral,
                )
            }
        }
        if (onToggle != null) {
            PluginBusySwitch(enabled, busy, onToggle)
        }
    }
}

// ── 网络共享（samba4：全局段仅编辑 + 共享列表） ──

private val SambaGlobalSchema = listOf(
    // interface 是 multiSelect 但LuCI 写空格串（非 uci list）——uciList=false 照抄勿"修正"
    UciFieldSpec(
        "interface", R.string.samba_interface, UciFieldType.MULTI_SELECT,
        candidates = UciCandidates.INTERFACES, hintRes = R.string.samba_interface_hint,
    ),
    UciFieldSpec("workgroup", R.string.samba_workgroup, UciFieldType.TEXT, placeholder = "WORKGROUP"),
    UciFieldSpec("description", R.string.samba_description, UciFieldType.TEXT),
    UciFieldSpec("enable_extra_tuning", R.string.samba_extra_tuning, UciFieldType.SWITCH),
    UciFieldSpec("disable_async_io", R.string.samba_force_sync_io, UciFieldType.SWITCH),
    UciFieldSpec("macos", R.string.samba_macos, UciFieldType.SWITCH),
    UciFieldSpec("allow_legacy_protocols", R.string.samba_legacy_protocols, UciFieldType.SWITCH),
    UciFieldSpec("disable_netbios", R.string.samba_disable_netbios, UciFieldType.SWITCH),
    UciFieldSpec("disable_ad_dc", R.string.samba_disable_ad_dc, UciFieldType.SWITCH),
    UciFieldSpec("disable_winbind", R.string.samba_disable_winbind, UciFieldType.SWITCH),
)

private val SambaShareSchema = listOf(
    UciFieldSpec("name", R.string.samba_share_name, UciFieldType.TEXT, required = true),
    UciFieldSpec(
        "path", R.string.samba_share_path, UciFieldType.TEXT, required = true,
        placeholder = "/mnt/sda1/share", pattern = "^[A-Za-z0-9/_.-]+$",
    ),
    UciFieldSpec("browseable", R.string.samba_browseable, UciFieldType.SWITCH, onValue = "yes", offValue = "no", default = "yes"),
    UciFieldSpec("read_only", R.string.samba_read_only, UciFieldType.SWITCH, onValue = "yes", offValue = "no", default = "no"),
    UciFieldSpec("force_root", R.string.samba_force_root, UciFieldType.SWITCH),
    UciFieldSpec("users", R.string.samba_users, UciFieldType.TEXT),
    UciFieldSpec("guest_ok", R.string.samba_guest_ok, UciFieldType.SWITCH, onValue = "yes", offValue = "no", default = "yes"),
    UciFieldSpec("guest_only", R.string.samba_guest_only, UciFieldType.SWITCH, onValue = "yes", offValue = "no", default = "no"),
    UciFieldSpec("inherit_owner", R.string.samba_inherit_owner, UciFieldType.SWITCH, onValue = "yes", offValue = "no", default = "no"),
    UciFieldSpec("create_mask", R.string.samba_create_mask, UciFieldType.TEXT, default = "0666", pattern = "^[0-7]{4}$"),
    UciFieldSpec("dir_mask", R.string.samba_dir_mask, UciFieldType.TEXT, default = "0777", pattern = "^[0-7]{4}$"),
    UciFieldSpec("vfs_objects", R.string.samba_vfs_objects, UciFieldType.TEXT),
    UciFieldSpec("timemachine", R.string.samba_timemachine, UciFieldType.SWITCH),
    UciFieldSpec("timemachine_maxsize", R.string.samba_tm_maxsize, UciFieldType.TEXT, pattern = "^[0-9]{1,5}$"),
)

// CyclomaticComplexMethod：组合级页 = 列表/编辑/弹窗三态机，拆分会把状态闭包打散成长参数表
// （ClientScreen 先例）；已按区块抽行组件降低复杂度
@Suppress("CyclomaticComplexMethod")
@Composable
fun SambaScreen(deviceId: String?, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: SambaViewModel = viewModel(factory = viewModelFactory { initializer { SambaViewModel(app) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val candidates by vm.candidates.collectAsStateWithLifecycle()
    val template by vm.template.collectAsStateWithLifecycle()
    val doneEvent by vm.doneEvent.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(deviceId) {
        vm.ensureLoaded(deviceId)
        vm.loadTemplate()
    }
    PluginWriteErrorEffect(state.writeErrorRes) { vm.consumeWriteError() }
    PluginDoneEventEffect(doneEvent) { vm.consumeDoneEvent() }
    // editing："global"=全局段（仅编辑）；"template"=smb.conf.template；PLUGIN_CREATE/section 名=共享段
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val global = state.entries.firstOrNull { it.type == "samba" }
    val shares = state.entries.filter { it.type == "sambashare" }
    val editEntry = state.entries.firstOrNull { it.section == editing }
    val editingGlobal = editing == "global"
    if (editing == "template") {
        SambaTemplateEditor(
            template = template,
            onBack = { editing = null },
            onSave = { text ->
                vm.saveTemplate(text) { ok ->
                    if (ok) {
                        editing = null
                        Toast.makeText(context, context.getString(R.string.plugin_save_success), Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
        return
    }
    LaunchedEffect(editing, state.loading) {
        val target = editing
        if (target != null && target != "global" && pluginEditorStale(target, PLUGIN_CREATE, editEntry, state.loading)) {
            editing = null
        }
    }
    if (editing != null) {
        UciEditPage(
            title = stringResource(
                when {
                    editingGlobal -> R.string.samba_edit_global
                    editEntry == null -> R.string.samba_add_share
                    else -> R.string.samba_edit_share
                },
            ),
            specs = if (editingGlobal) SambaGlobalSchema else SambaShareSchema,
            entry = if (editingGlobal) global else editEntry,
            candidates = candidates,
            allowDelete = !editingGlobal,
            highRisk = false,
            saving = state.saving,
            onDelete = if (editEntry != null) ({ confirmDelete = true }) else null,
            onCancel = { editing = null },
            onSave = { values ->
                vm.submit(
                    create = !editingGlobal && editEntry == null,
                    sectionType = if (editingGlobal) "samba" else "sambashare",
                    editSection = if (editingGlobal) global?.section else editEntry?.section,
                    values = values,
                ) { ok -> if (ok) editing = null }
            },
        )
    } else {
        PluginPage(stringResource(R.string.samba_title), R.string.desc_samba, onBack, state.refreshing, vm::refresh) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            ) {
                // 全局设置（仅编辑；段缺失不可编辑，与 LuCI 全局设置守卫一致）
                SambaGlobalCard(global) { editing = "global" }
                // 模板编辑入口（对齐 LuCI Edit Template tab：smb.conf.template 文本编辑）
                Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Column(
                        Modifier.fillMaxWidth().clickable { editing = "template" }.padding(14.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.samba_edit_template),
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                "/etc/samba/smb.conf.template ›",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            stringResource(R.string.samba_template_hint),
                            Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                PluginAddHead(stringResource(R.string.samba_section_shares), stringResource(R.string.samba_add_share)) {
                    editing = PLUGIN_CREATE
                }
                SambaSharesBlock(state, shares, onRetry = vm::retry, onEdit = { editing = it })
                Spacer(Modifier.height(12.dp))
            }
        }
    }
    if (confirmDelete) {
        val target = editEntry
        if (target != null) {
            PluginConfirmDialog(
                title = stringResource(R.string.plugin_delete),
                body = stringResource(R.string.plugin_delete_confirm),
                confirmText = stringResource(R.string.plugin_delete),
                onConfirm = {
                    confirmDelete = false
                    vm.remove(target.section) { ok -> if (ok) editing = null }
                },
                onDismiss = { confirmDelete = false },
            )
        } else {
            confirmDelete = false
        }
    }
}

/** smb.conf.template 文本编辑页（对齐 LuCI Edit Template：trim+CRLF→LF 写回，samba4 restart 生效） */
@Composable
private fun SambaTemplateEditor(
    template: SambaViewModel.TemplateUi,
    onBack: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(template.text) }
    ToolPage(title = stringResource(R.string.samba_edit_template), onBack = onBack) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Text(
                stringResource(R.string.samba_template_hint),
                Modifier.padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(onClick = onBack, enabled = !template.saving, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.plugin_cancel))
                }
                Button(onClick = { onSave(text) }, enabled = !template.saving, modifier = Modifier.weight(2f)) {
                    if (template.saving) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.plugin_save))
                    }
                }
            }
        }
    }
}

/** 全局设置卡（摘要行 + macOS 徽章；global 缺失时不可编辑） */
@Composable
private fun SambaGlobalCard(global: UciEntry?, onEdit: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(
            Modifier.fillMaxWidth().clickable(enabled = global != null, onClick = onEdit).padding(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.samba_global),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(R.string.plugin_edit),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (global != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            InfoRow(stringResource(R.string.samba_workgroup), global?.first("workgroup") ?: "WORKGROUP")
            InfoRow(
                stringResource(R.string.samba_interface),
                global?.first("interface")?.takeIf { it.isNotBlank() } ?: "lan",
            )
            val macos = global?.first("macos") == "1"
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.samba_macos),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ToolBadge(
                    stringResource(if (macos) R.string.plugin_enabled else R.string.plugin_disabled),
                    if (macos) ToolBadgeKind.Positive else ToolBadgeKind.Neutral,
                )
            }
        }
    }
}

/** 共享目录区块（加载/失败/空/数据四态） */
@Composable
private fun SambaSharesBlock(
    state: dev.wrtctrl.viewmodel.plugin.UciListUiState,
    shares: List<UciEntry>,
    onRetry: () -> Unit,
    onEdit: (String) -> Unit,
) {
    when {
        state.loading -> ToolStateBox(spinner = true)
        shares.isEmpty() && state.loadFailed -> ToolErrorRetry(stringResource(R.string.common_load_failed), onRetry = onRetry)
        shares.isEmpty() -> PluginEmptyHint(stringResource(R.string.samba_empty))
        else -> Card(Modifier.fillMaxWidth()) {
            Column {
                shares.forEachIndexed { index, share ->
                    SambaShareRow(share) { onEdit(share.section) }
                    if (index != shares.lastIndex) HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SambaShareRow(share: UciEntry, onEdit: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(
            share.first("name") ?: share.section,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            share.first("path") ?: "-",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val readOnly = share.first("read_only") == "yes"
            ToolBadge(
                stringResource(if (readOnly) R.string.samba_read_only else R.string.samba_read_write),
                if (readOnly) ToolBadgeKind.Info else ToolBadgeKind.Positive,
            )
            if (share.first("guest_ok") == "yes") {
                ToolBadge(stringResource(R.string.samba_guest), ToolBadgeKind.Warning)
            }
        }
    }
}

// ── USB 打印机（发现区 + 绑定列表） ──

private fun usbPrinterPortOptions(): List<UciOption> =
    (0..9).map { UciOption(it.toString(), label = (9100 + it).toString()) }

private val UsbPrinterEnabledSpec = UciFieldSpec(
    "enabled", R.string.usb_printer_field_enabled, UciFieldType.SWITCH, default = "1",
)

private val UsbPrinterSchema = listOf(
    UsbPrinterEnabledSpec,
    // device：对齐 LuCI form.Value——探测结果为候选建议、无打印机时可手填（SELECT 会把空候选变死路）
    UciFieldSpec(
        "device", R.string.usb_printer_field_device, UciFieldType.TEXT, required = true,
        candidates = UciCandidates.PRINTERS,
    ),
    UciFieldSpec(
        "port", R.string.usb_printer_field_port, UciFieldType.SELECT, required = true, default = "0",
        options = usbPrinterPortOptions(),
    ),
    UciFieldSpec(
        // 与 LuCI 一致：bind 在 LuCI 是「接口」下拉（选接口写其 IP）——整行下拉，0.0.0.0=所有接口
        "bind", R.string.usb_printer_field_iface, UciFieldType.SELECT, default = "0.0.0.0",
        options = listOf(UciOption("0.0.0.0", R.string.usb_printer_bind_all)),
        candidates = UciCandidates.IFADDRS,
        hintRes = R.string.usb_printer_bind_hint,
    ),
    UciFieldSpec("bidirectional", R.string.usb_printer_field_bidirectional, UciFieldType.SWITCH, default = "0"),
)

@Suppress("CyclomaticComplexMethod")
@Composable
fun UsbPrinterScreen(deviceId: String?, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: UsbPrinterViewModel = viewModel(factory = viewModelFactory { initializer { UsbPrinterViewModel(app) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val candidates by vm.candidates.collectAsStateWithLifecycle()
    val printers by vm.printers.collectAsStateWithLifecycle()
    val scanError by vm.scanError.collectAsStateWithLifecycle()
    val doneEvent by vm.doneEvent.collectAsStateWithLifecycle()
    LaunchedEffect(deviceId) {
        vm.ensureLoaded(deviceId)
        vm.loadPrinters()
    }
    PluginWriteErrorEffect(state.writeErrorRes) { vm.consumeWriteError() }
    PluginDoneEventEffect(doneEvent) { vm.consumeDoneEvent() }
    val context = LocalContext.current
    LaunchedEffect(scanError) {
        if (scanError == true) {
            Toast.makeText(context, context.getString(R.string.common_load_failed), Toast.LENGTH_SHORT).show()
            vm.consumeScanError()
        }
    }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val editEntry = state.entries.firstOrNull { it.section == editing }
    LaunchedEffect(editing, state.loading) {
        val target = editing
        if (pluginEditorStale(target, PLUGIN_CREATE, editEntry, state.loading)) editing = null
    }
    if (editing != null) {
        UciEditPage(
            title = stringResource(if (editEntry == null) R.string.usb_printer_add_binding else R.string.usb_printer_edit_binding),
            specs = UsbPrinterSchema,
            entry = editEntry,
            candidates = candidates,
            allowDelete = true,
            highRisk = false,
            saving = state.saving,
            onDelete = if (editEntry != null) ({ confirmDelete = true }) else null,
            onCancel = { editing = null },
            onSave = { values ->
                vm.submit(editEntry == null, "printer", editEntry?.section, values) { ok ->
                    if (ok) {
                        editing = null
                        vm.loadPrinters()
                    }
                }
            },
        )
    } else {
        PluginPage(stringResource(R.string.usb_printer_title), null, onBack, state.refreshing, vm::refresh) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            ) {
                Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(
                        stringResource(R.string.usb_printer_hint),
                        Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PluginAddHead(stringResource(R.string.usb_printer_detected), stringResource(R.string.common_retry)) {
                    vm.loadPrinters()
                }
                if (printers.details.isEmpty()) {
                    PluginEmptyHint(stringResource(R.string.usb_printer_no_printers))
                } else {
                    Card(Modifier.fillMaxWidth()) {
                        Column {
                            printers.details.forEachIndexed { index, p ->
                                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                                    Text(
                                        p.description.ifEmpty { p.model.ifEmpty { p.devname } },
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        "${p.devname} · ${p.id} · ${p.devicePath}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (index != printers.details.lastIndex) HorizontalDivider()
                            }
                        }
                    }
                }
                PluginAddHead(stringResource(R.string.usb_printer_bindings), stringResource(R.string.usb_printer_add_binding)) {
                    editing = PLUGIN_CREATE
                }
                when {
                    state.loading -> ToolStateBox(spinner = true)
                    state.entries.isEmpty() && state.loadFailed ->
                        ToolErrorRetry(stringResource(R.string.common_load_failed), onRetry = vm::retry)
                    state.entries.isEmpty() -> PluginEmptyHint(stringResource(R.string.usb_printer_no_bindings))
                    else -> Card(Modifier.fillMaxWidth()) {
                        Column {
                            state.entries.forEachIndexed { index, entry ->
                                val devLabel = candidates[UciCandidates.PRINTERS.kind]
                                    ?.firstOrNull { it.value == entry.first("device") }?.label
                                    ?: entry.first("device")
                                    ?: stringResource(R.string.usb_printer_no_printers)
                                UsbPrinterBindingRow(
                                    entry = entry,
                                    busy = state.busySection == entry.section,
                                    deviceLabel = devLabel,
                                    onToggle = { on -> vm.toggle(entry, UciEditorLogic.switchPatch(UsbPrinterEnabledSpec, on)) },
                                    onEdit = { editing = entry.section },
                                )
                                if (index != state.entries.lastIndex) HorizontalDivider()
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
    if (confirmDelete) {
        val target = editEntry
        if (target != null) {
            PluginConfirmDialog(
                title = stringResource(R.string.plugin_delete),
                body = stringResource(R.string.plugin_delete_confirm),
                confirmText = stringResource(R.string.plugin_delete),
                onConfirm = {
                    confirmDelete = false
                    vm.remove(target.section) { ok -> if (ok) editing = null }
                },
                onDismiss = { confirmDelete = false },
            )
        } else {
            confirmDelete = false
        }
    }
}

@Composable
private fun UsbPrinterBindingRow(
    entry: UciEntry,
    busy: Boolean,
    deviceLabel: String,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
) {
    val enabled = entry.first("enabled") == "1"
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(deviceLabel, style = MaterialTheme.typography.bodyMedium)
            Text(
                buildString {
                    append(":")
                    append(entry.first("port")?.toIntOrNull()?.let { (9100 + it).toString() } ?: "?")
                    append(" · ")
                    append(entry.first("bind") ?: "0.0.0.0")
                    if (!enabled) append(" · ").append(stringResource(R.string.plugin_disabled))
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PluginBusySwitch(enabled, busy, onToggle)
    }
}

// ── 超级网络唤醒 ──

private const val WOL_CRON_DEPENDS = "scheduled"

private val WolSchema = listOf(
    UciFieldSpec("name", R.string.wolultra_name, UciFieldType.TEXT, required = true, groupRes = R.string.wolultra_group_general),
    UciFieldSpec(
        "macaddr", R.string.wolultra_macaddr, UciFieldType.TEXT, required = true,
        candidates = UciCandidates.HOSTHINTS_MAC, placeholder = "00:11:22:33:44:55",
        pattern = "^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$", patternErrorRes = R.string.wolultra_mac_invalid,
        groupRes = R.string.wolultra_group_general,
    ),
    UciFieldSpec(
        "maceth", R.string.wolultra_iface, UciFieldType.DEVICE_SELECT, required = true, default = "br-lan",
        groupRes = R.string.wolultra_group_general,
    ),
    UciFieldSpec("scheduled", R.string.wolultra_scheduled, UciFieldType.SWITCH, default = "0", groupRes = R.string.wolultra_group_schedule),
    UciFieldSpec(
        "cron", R.string.wolultra_cron, UciFieldType.TEXT, default = "0 0 * * *",
        placeholder = "0 0 * * *",
        dependsKey = WOL_CRON_DEPENDS, dependsValues = listOf("1"),
        pattern = UciEditorLogic.wolCronPattern(), patternErrorRes = R.string.wolultra_cron_invalid,
        groupRes = R.string.wolultra_group_schedule,
    ),
)

@Suppress("CyclomaticComplexMethod")
@Composable
fun WolScreen(deviceId: String?, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: WolViewModel = viewModel(factory = viewModelFactory { initializer { WolViewModel(app) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val candidates by vm.candidates.collectAsStateWithLifecycle()
    val wakeUi by vm.wake.collectAsStateWithLifecycle()
    val doneEvent by vm.doneEvent.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(deviceId) { vm.ensureLoaded(deviceId) }
    PluginWriteErrorEffect(state.writeErrorRes) { vm.consumeWriteError() }
    PluginDoneEventEffect(doneEvent) { vm.consumeDoneEvent() }
    // 唤醒事件：成功 toast / 失败弹窗（输出尾串）
    LaunchedEffect(wakeUi.sentName) {
        wakeUi.sentName?.let {
            Toast.makeText(context, context.getString(R.string.wolultra_wake_sent, it), Toast.LENGTH_SHORT).show()
            vm.consumeSent()
        }
    }
    if (wakeUi.failed != null) {
        val failed = wakeUi.failed
        AlertDialog(
            onDismissRequest = { vm.consumeFailed() },
            title = { Text(stringResource(R.string.wolultra_wake_failed)) },
            text = {
                // 与 LuCI 行为一致：正文=失败输出（stdout/stderr），空则退通用失败文案
                val (_, out) = failed!!
                Text(out.ifEmpty { stringResource(R.string.wolultra_wake_failed) })
            },
            confirmButton = {
                TextButton(onClick = { vm.consumeFailed() }) { Text(stringResource(R.string.common_close)) }
            },
        )
    }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val editEntry = state.entries.firstOrNull { it.section == editing }
    LaunchedEffect(editing, state.loading) {
        val target = editing
        if (pluginEditorStale(target, PLUGIN_CREATE, editEntry, state.loading)) editing = null
    }
    if (editing != null) {
        UciEditPage(
            title = stringResource(if (editEntry == null) R.string.wolultra_add_host else R.string.wolultra_edit_host),
            specs = WolSchema,
            // cron 预填：cron 字段优先、缺失回落五字段拼接（对齐 LuCI cronExpression/init.d）
            entry = editEntry?.let { e ->
                e.copy(options = e.options + ("cron" to listOf(UciEditorLogic.wolCronExpression(e))))
            },
            candidates = candidates,
            allowDelete = true,
            highRisk = false,
            saving = state.saving,
            onDelete = if (editEntry != null) ({ confirmDelete = true }) else null,
            onCancel = { editing = null },
            onSave = { values ->
                vm.submit(editEntry == null, "macclient", editEntry?.section, values) { ok -> if (ok) editing = null }
            },
        )
    } else {
        PluginPage(stringResource(R.string.wolultra_title), R.string.desc_wolultra, onBack, state.refreshing, vm::refresh) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                PluginAddHead(stringResource(R.string.wolultra_section), stringResource(R.string.wolultra_add_host)) {
                    editing = PLUGIN_CREATE
                }
                when {
                    state.loading -> ToolStateBox(spinner = true)
                    state.entries.isEmpty() && state.loadFailed ->
                        ToolErrorRetry(stringResource(R.string.common_load_failed), onRetry = vm::retry)
                    state.entries.isEmpty() -> PluginEmptyHint(stringResource(R.string.wolultra_empty))
                    else -> Card(Modifier.fillMaxWidth()) {
                        Column {
                            state.entries.forEachIndexed { index, entry ->
                                WolRow(
                                    entry = entry,
                                    waking = wakeUi.wakingSection == entry.section,
                                    onWake = {
                                        vm.wake(entry, entry.first("name") ?: entry.first("macaddr") ?: entry.section)
                                    },
                                    onEdit = { editing = entry.section },
                                )
                                if (index != state.entries.lastIndex) HorizontalDivider()
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
    if (confirmDelete) {
        val target = editEntry
        if (target != null) {
            PluginConfirmDialog(
                title = stringResource(R.string.plugin_delete),
                body = stringResource(R.string.plugin_delete_confirm),
                confirmText = stringResource(R.string.plugin_delete),
                onConfirm = {
                    confirmDelete = false
                    vm.remove(target.section) { ok -> if (ok) editing = null }
                },
                onDismiss = { confirmDelete = false },
            )
        } else {
            confirmDelete = false
        }
    }
}

@Composable
private fun WolRow(entry: UciEntry, waking: Boolean, onWake: () -> Unit, onEdit: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                entry.first("name") ?: entry.section,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = onWake, enabled = !waking) {
                if (waking) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.wolultra_wake))
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onEdit),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                entry.first("macaddr") ?: "-",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                entry.first("maceth") ?: "br-lan",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (entry.first("scheduled") == "1") {
            Text(
                // 实际生效表达式：cron 字段优先、缺失回落五字段（与 init.d 脚本一致）
                UciEditorLogic.wolCronExpression(entry),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

// ── 简单列表屏共享骨架（工厂 lambda 注入 VM；行内开关由 toggleSpec 声明） ──

@Composable
private fun <T : UciPluginViewModel> SimplePluginScreen(
    deviceId: String?,
    onBack: () -> Unit,
    titleRes: Int,
    subtitleRes: Int,
    sectionTitleRes: Int,
    addActionRes: Int,
    emptyRes: Int,
    sectionType: String,
    schema: List<UciFieldSpec>,
    createTitleRes: Int,
    editTitleRes: Int,
    toggleSpec: UciFieldSpec?,
    createVm: (Application) -> T,
    vmClass: Class<T>,
    row: @Composable (UciEntry, Boolean, ((Boolean) -> Unit)?, () -> Unit) -> Unit,
) {
    val app = LocalContext.current.applicationContext as Application
    // 泛型包装不能用 reified viewModel<T>()/initializer<T>()：显式 Class + 手写 Factory
    val vm: T = viewModel(
        modelClass = vmClass,
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = createVm(app) as VM
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val candidates by vm.candidates.collectAsStateWithLifecycle()
    val doneEvent by vm.doneEvent.collectAsStateWithLifecycle()
    LaunchedEffect(deviceId) { vm.ensureLoaded(deviceId) }
    PluginWriteErrorEffect(state.writeErrorRes) { vm.consumeWriteError() }
    PluginDoneEventEffect(doneEvent) { vm.consumeDoneEvent() }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val editEntry = state.entries.firstOrNull { it.section == editing }
    // 进程复活边界：编辑目标已不在（列表尚未回读或已被删）→ 回列表
    LaunchedEffect(editing, state.loading) {
        val target = editing
        if (pluginEditorStale(target, PLUGIN_CREATE, editEntry, state.loading)) editing = null
    }
    if (editing != null) {
        UciEditPage(
            title = stringResource(if (editEntry == null) createTitleRes else editTitleRes),
            specs = schema,
            entry = editEntry,
            candidates = candidates,
            allowDelete = true,
            highRisk = false,
            saving = state.saving,
            onDelete = if (editEntry != null) ({ confirmDelete = true }) else null,
            onCancel = { editing = null },
            onSave = { values ->
                vm.submit(editEntry == null, sectionType, editEntry?.section, values) { ok -> if (ok) editing = null }
            },
        )
    } else {
        PluginPage(stringResource(titleRes), subtitleRes, onBack, state.refreshing, vm::refresh) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                PluginAddHead(stringResource(sectionTitleRes), stringResource(addActionRes)) {
                    editing = PLUGIN_CREATE
                }
                when {
                    state.loading -> ToolStateBox(spinner = true)
                    state.entries.isEmpty() && state.loadFailed ->
                        ToolErrorRetry(stringResource(R.string.common_load_failed), onRetry = vm::retry)
                    state.entries.isEmpty() -> PluginEmptyHint(stringResource(emptyRes))
                    else -> Card(Modifier.fillMaxWidth()) {
                        Column {
                            state.entries.forEachIndexed { index, entry ->
                                row(
                                    entry,
                                    state.busySection == entry.section,
                                    toggleSpec?.let { spec ->
                                        { on -> vm.toggle(entry, UciEditorLogic.switchPatch(spec, on)) }
                                    },
                                ) { editing = entry.section }
                                if (index != state.entries.lastIndex) HorizontalDivider()
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
    if (confirmDelete) {
        val target = editEntry
        if (target != null) {
            PluginConfirmDialog(
                title = stringResource(R.string.plugin_delete),
                body = stringResource(R.string.plugin_delete_confirm),
                confirmText = stringResource(R.string.plugin_delete),
                onConfirm = {
                    confirmDelete = false
                    vm.remove(target.section) { ok -> if (ok) editing = null }
                },
                onDismiss = { confirmDelete = false },
            )
        } else {
            confirmDelete = false
        }
    }
}
