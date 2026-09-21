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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.wrtctrl.R
import dev.wrtctrl.ui.component.PollingGate
import dev.wrtctrl.viewmodel.plugin.UpnpRuleUi
import dev.wrtctrl.viewmodel.plugin.UpnpViewModel
import dev.wrtctrl.viewmodel.plugin.UciEntry
import dev.wrtctrl.viewmodel.plugin.UciFieldSpec
import dev.wrtctrl.viewmodel.plugin.UciFieldType
import dev.wrtctrl.viewmodel.plugin.UciOption

/**
 * UPnP / NAT-PMP 页：活跃映射（luci.upnp get_status 5s 轮询 + delete_rule，
 * ）+ 服务设置（config 段仅编辑，depends 联动）+ ACL（perm_rule 列表）。
 * schema 与 LuCI UPnP 页一致；轮询纪律与工具页一致（delay 醒来复查门控）。
 */

private val UpnpConfigSchema = listOf(
    UciFieldSpec("enabled", R.string.upnp_enable, UciFieldType.SWITCH),
    UciFieldSpec("enable_upnp", R.string.upnp_enable_upnp, UciFieldType.SWITCH, default = "1"),
    UciFieldSpec("enable_natpmp", R.string.upnp_enable_natpmp, UciFieldType.SWITCH, default = "1"),
    UciFieldSpec("ext_allow_private_ipv4", R.string.upnp_ext_allow_private, UciFieldType.SWITCH),
    UciFieldSpec("igdv1", R.string.upnp_igdv1, UciFieldType.SWITCH, default = "1", dependsKey = "enable_upnp", dependsValues = listOf("1")),
    UciFieldSpec(
        "download", R.string.upnp_download, UciFieldType.TEXT, pattern = "^[0-9]*$",
        dependsKey = "enable_upnp", dependsValues = listOf("1"),
    ),
    UciFieldSpec(
        "upload", R.string.upnp_upload, UciFieldType.TEXT, pattern = "^[0-9]*$",
        dependsKey = "enable_upnp", dependsValues = listOf("1"),
    ),
    UciFieldSpec("use_stun", R.string.upnp_use_stun, UciFieldType.SWITCH),
    UciFieldSpec(
        "stun_host", R.string.upnp_stun_host, UciFieldType.TEXT, pattern = "^[a-zA-Z0-9.:_-]+$",
        dependsKey = "use_stun", dependsValues = listOf("1"),
    ),
    UciFieldSpec(
        "stun_port", R.string.upnp_stun_port, UciFieldType.TEXT, pattern = "^[0-9]*$",
        dependsKey = "use_stun", dependsValues = listOf("1"),
    ),
    UciFieldSpec(
        "secure_mode", R.string.upnp_secure_mode, UciFieldType.SWITCH, default = "1",
        dependsKey = "enable_upnp", dependsValues = listOf("1"),
    ),
    UciFieldSpec(
        "system_uptime", R.string.upnp_system_uptime, UciFieldType.SWITCH, default = "1",
        dependsKey = "enable_upnp", dependsValues = listOf("1"),
    ),
    UciFieldSpec("log_output", R.string.upnp_log_output, UciFieldType.SWITCH),
    // 高级组（对齐 LuCI advanced tab）
    UciFieldSpec(
        "notify_interval", R.string.upnp_field_notify_interval, UciFieldType.TEXT,
        pattern = "^[0-9]*$", groupRes = R.string.upnp_g_advanced,
    ),
    UciFieldSpec(
        "port", R.string.upnp_field_port, UciFieldType.TEXT, pattern = "^[0-9]*$",
        groupRes = R.string.upnp_g_advanced,
    ),
    UciFieldSpec(
        "presentation_url", R.string.upnp_field_presentation_url, UciFieldType.TEXT,
        groupRes = R.string.upnp_g_advanced,
    ),
    UciFieldSpec("uuid", R.string.upnp_field_uuid, UciFieldType.TEXT, groupRes = R.string.upnp_g_advanced),
    UciFieldSpec(
        "model_number", R.string.upnp_field_model_number, UciFieldType.TEXT,
        groupRes = R.string.upnp_g_advanced,
    ),
    UciFieldSpec(
        "serial_number", R.string.upnp_field_serial_number, UciFieldType.TEXT,
        groupRes = R.string.upnp_g_advanced,
    ),
    UciFieldSpec(
        "upnp_lease_file", R.string.upnp_field_lease_file, UciFieldType.TEXT,
        groupRes = R.string.upnp_g_advanced,
    ),
)

private val UpnpAclSchema = listOf(
    UciFieldSpec("comment", R.string.upnp_comment, UciFieldType.TEXT, required = true),
    UciFieldSpec(
        "int_addr", R.string.upnp_int_addr, UciFieldType.TEXT,
        placeholder = "0.0.0.0/0", pattern = "^[0-9a-zA-Z./:_-]+$",
    ),
    UciFieldSpec(
        "int_ports", R.string.upnp_int_ports, UciFieldType.TEXT,
        placeholder = "1-65535", pattern = "^[0-9,-]+$",
    ),
    UciFieldSpec(
        "ext_ports", R.string.upnp_ext_ports, UciFieldType.TEXT,
        placeholder = "1-65535", pattern = "^[0-9,-]+$",
    ),
    UciFieldSpec(
        "action", R.string.upnp_action, UciFieldType.SELECT, required = true, default = "allow",
        options = listOf(UciOption("allow", R.string.upnp_allow), UciOption("deny", R.string.upnp_deny)),
    ),
)

// PLUGIN_CREATE 共享定义见 PluginCommon

// CyclomaticComplexMethod：活跃映射/ACL 区块已抽组件，剩余为编辑器/弹窗三态机固有复杂度
// （ClientScreen 先例）
@Suppress("CyclomaticComplexMethod")
@Composable
fun UpnpScreen(deviceId: String?, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: UpnpViewModel = viewModel(factory = viewModelFactory { initializer { UpnpViewModel(app) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val candidates by vm.candidates.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val deleteEvent by vm.deleteEvent.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(deviceId) {
        vm.ensureLoaded(deviceId)
        vm.loadRules()
    }
    // 活跃映射 5s 轮询门控：覆盖页仅打开时组合 + ON_RESUME/ON_PAUSE
    PollingGate(onActiveChange = vm::setVisible)
    PluginWriteErrorEffect(state.writeErrorRes) { vm.consumeWriteError() }
    val doneEvent by vm.doneEvent.collectAsStateWithLifecycle()
    PluginDoneEventEffect(doneEvent) { vm.consumeDoneEvent() }
    LaunchedEffect(deleteEvent) {
        deleteEvent?.let { ok ->
            Toast.makeText(
                context,
                context.getString(if (ok) R.string.upnp_delete_success else R.string.upnp_delete_failed),
                Toast.LENGTH_SHORT,
            ).show()
            vm.consumeDeleteEvent()
        }
    }
    var editType by rememberSaveable { mutableStateOf<String?>(null) } // "config" | "acl"
    var editSection by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDeleteRule by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDeleteAcl by rememberSaveable { mutableStateOf<String?>(null) }
    // 服务段识别与 LuCI 行为一致 `.type==='config' || name==='config'`：标准固件是 config upnpd 'config'
    // （类型 upnpd、段名 config），只按类型匹配会永久 miss → 服务设置卡死
    val config = state.entries.firstOrNull { it.type == "config" || it.section == "config" }
    val acls = state.entries.filter { it.type == "perm_rule" }
    val editEntry = state.entries.firstOrNull { it.section == editSection }
    // 进程复活边界：config 段缺失不可编辑（create=false 无 section 会 NPE，与 LuCI 守卫一致）；ACL 目标已删回列表
    LaunchedEffect(editType, editSection, state.loading) {
        val close = when {
            editType == "config" && config == null && !state.loading -> true
            editType == "acl" && editSection != PLUGIN_CREATE && editEntry == null && !state.loading -> true
            else -> false
        }
        if (close) {
            editType = null
            editSection = null
        }
    }
    if (editType != null) {
        val isConfig = editType == "config"
        UpnpEditorCover(
            isConfig = isConfig,
            config = config,
            editEntry = editEntry,
            candidates = candidates,
            saving = state.saving,
            confirmDeleteAcl = confirmDeleteAcl != null,
            onCancel = { editType = null; editSection = null },
            onDelete = { confirmDeleteAcl = editSection },
            onSave = { values ->
                vm.submit(
                    create = !isConfig && editEntry == null,
                    sectionType = if (isConfig) "config" else "perm_rule",
                    editSection = if (isConfig) config?.section else editEntry?.section,
                    values = values,
                ) { ok -> if (ok) { editType = null; editSection = null } }
            },
            onRemove = { section ->
                vm.remove(section) { ok -> if (ok) { editType = null; editSection = null } }
            },
        )
        return
    }

    PluginPage(stringResource(R.string.upnp_title), null, onBack, state.refreshing, vm::refresh) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            // 活跃端口映射（luci.upnp 通道，5s 静默刷新）
            UpnpActiveCard(active) { rule -> confirmDeleteRule = rule.num }
            // 服务设置（config 段仅编辑；段缺失不可编辑，与 LuCI 配置守卫一致）
            Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = config != null) { editType = "config"; editSection = config?.section }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.upnp_service_settings), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.plugin_edit),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val running = config?.first("enabled") == "1"
                    ToolBadge(
                        stringResource(if (running) R.string.upnp_running else R.string.upnp_stopped),
                        if (running) ToolBadgeKind.Positive else ToolBadgeKind.Neutral,
                    )
                }
            }
            // ACL 规则
            PluginAddHead(stringResource(R.string.upnp_section_acl), stringResource(R.string.upnp_add_acl)) {
                editType = "acl"; editSection = PLUGIN_CREATE
            }
            Text(
                stringResource(R.string.upnp_acl_desc),
                Modifier.padding(horizontal = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            UpnpAclBlock(state.loading, state.loadFailed, acls, vm::retry) { editType = "acl"; editSection = it }
            Spacer(Modifier.height(12.dp))
        }
    }
    // 活跃映射删除确认
    if (confirmDeleteRule != null) {
        PluginConfirmDialog(
            title = stringResource(R.string.upnp_title),
            body = stringResource(R.string.upnp_delete_confirm),
            confirmText = stringResource(R.string.plugin_delete),
            onConfirm = {
                val num = confirmDeleteRule
                confirmDeleteRule = null
                val rule = active.rules.firstOrNull { it.num == num }
                if (rule != null) vm.deleteRule(rule)
            },
            onDismiss = { confirmDeleteRule = null },
        )
    }
}

/** 编辑器覆盖层：config（仅编辑）与 perm_rule 两类 schema 分流 + 删除确认弹窗 */
@Composable
private fun UpnpEditorCover(
    isConfig: Boolean,
    config: UciEntry?,
    editEntry: UciEntry?,
    candidates: Map<String, List<UciOption>>,
    saving: Boolean,
    confirmDeleteAcl: Boolean,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onSave: (org.json.JSONObject) -> Unit,
    onRemove: (String) -> Unit,
) {
    UciEditPage(
        title = stringResource(
            when {
                isConfig -> R.string.upnp_edit_config
                editEntry == null -> R.string.upnp_add_acl
                else -> R.string.upnp_edit_acl
            },
        ),
        specs = if (isConfig) UpnpConfigSchema else UpnpAclSchema,
        entry = if (isConfig) config else editEntry,
        candidates = candidates,
        allowDelete = !isConfig,
        highRisk = false,
        saving = saving,
        onDelete = if (!isConfig && editEntry != null) onDelete else null,
        onCancel = onCancel,
        onSave = onSave,
    )
    if (confirmDeleteAcl) {
        PluginConfirmDialog(
            title = stringResource(R.string.plugin_delete),
            body = stringResource(R.string.plugin_delete_confirm),
            confirmText = stringResource(R.string.plugin_delete),
            onConfirm = {
                editEntry?.let { onRemove(it.section) }
            },
            onDismiss = onCancel,
        )
    }
}

/** 活跃端口映射卡（luci.upnp 通道；行删除回调带确认由调用方处理） */
@Composable
private fun UpnpActiveCard(active: UpnpViewModel.ActiveUi, onDeleteRule: (dev.wrtctrl.viewmodel.plugin.UpnpRuleUi) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.upnp_active_maps),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                )
                ToolBadge(stringResource(R.string.upnp_auto_refresh), ToolBadgeKind.Info)
            }
            HorizontalDivider()
            if (active.rules.isEmpty()) {
                PluginEmptyHint(stringResource(R.string.upnp_no_rules))
            } else {
                active.rules.forEachIndexed { index, rule ->
                    UpnpRuleRow(
                        rule = rule,
                        busy = active.deleting == rule.num,
                        onDelete = { onDeleteRule(rule) },
                    )
                    if (index != active.rules.lastIndex) HorizontalDivider()
                }
            }
        }
    }
}

/** ACL 列表区块（加载/失败/空/数据四态） */
@Composable
private fun UpnpAclBlock(
    loading: Boolean,
    loadFailed: Boolean,
    acls: List<UciEntry>,
    onRetry: () -> Unit,
    onEdit: (String) -> Unit,
) {
    when {
        loading -> ToolStateBox(spinner = true)
        acls.isEmpty() && loadFailed -> ToolErrorRetry(stringResource(R.string.common_load_failed), onRetry = onRetry)
        acls.isEmpty() -> PluginEmptyHint(stringResource(R.string.upnp_no_acl))
        else -> Card(Modifier.fillMaxWidth()) {
            Column {
                acls.forEachIndexed { index, acl ->
                    UpnpAclRow(acl) { onEdit(acl.section) }
                    if (index != acls.lastIndex) HorizontalDivider()
                }
            }
        }
    }
}

/** 租约剩余秒数 → 紧凑倒计时（对齐 LuCI expires 列：1h 05m 30s 风格）；null=不过期不显 */
private fun formatExpires(sec: Long): String? {
    if (sec <= 0) return null
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}

@Composable
private fun UpnpRuleRow(rule: UpnpRuleUi, busy: Boolean, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                rule.descr.ifEmpty { rule.hostHint.ifEmpty { rule.proto } },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(rule.proto).append(' ').append(rule.extPort)
                    append(" → ").append(rule.intAddr).append(':').append(rule.intPort)
                    formatExpires(rule.expires)?.let { append(" · ").append(it) }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (busy) {
            CircularProgressIndicator(Modifier.padding(start = 8.dp).size(18.dp), strokeWidth = 2.dp)
        } else {
            TextButton(onClick = onDelete) { Text(stringResource(R.string.plugin_delete)) }
        }
    }
}

@Composable
private fun UpnpAclRow(acl: UciEntry, onEdit: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                acl.first("comment") ?: acl.section,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "${acl.first("int_addr") ?: "*"}:${acl.first("int_ports") ?: "*"} → ${acl.first("ext_ports") ?: "*"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val allow = acl.first("action") != "deny"
        ToolBadge(
            stringResource(if (allow) R.string.upnp_allow else R.string.upnp_deny),
            if (allow) ToolBadgeKind.Positive else ToolBadgeKind.Error,
        )
    }
}
