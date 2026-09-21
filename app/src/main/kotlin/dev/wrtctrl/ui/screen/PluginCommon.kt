@file:Suppress("TooManyFunctions")

package dev.wrtctrl.ui.screen

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.wrtctrl.R
import dev.wrtctrl.viewmodel.AppRegistry
import dev.wrtctrl.viewmodel.plugin.UciCandidates
import dev.wrtctrl.viewmodel.plugin.UciEditorDraft
import dev.wrtctrl.viewmodel.plugin.UciEditorLogic
import dev.wrtctrl.viewmodel.plugin.UciEntry
import dev.wrtctrl.viewmodel.plugin.UciFieldType
import dev.wrtctrl.viewmodel.plugin.UciFieldSpec
import dev.wrtctrl.viewmodel.plugin.UciOption
import org.json.JSONObject

/**
 * 插件页共享件：
 * PluginRouter 分支 + PluginPage 覆盖页骨架（返回头 + 下拉刷新 + 副标题）+ schema 驱动
 * 全屏编辑页（有意偏离旧中央弹窗——14 字段弹窗放不下）+ 高危确认弹窗。
 * 覆盖页必须自带 BackHandler（ToolPage 已含），否则系统返回直接退桌面。
 *
 * TooManyFunctions：本文件是共享件聚合（路由/骨架/组件/编辑页/弹窗），拆文件只为过阈值伤内聚。
 */

/** 新建编辑器键（区分于 section 名；各插件屏共用同一语义） */
internal const val PLUGIN_CREATE = "__create__"

/** 编辑目标失效判定（进程复活边界：编辑键既非新建、目标又不在已回读列表中 → 回列表） */
internal fun pluginEditorStale(editing: String?, createKey: String, entry: UciEntry?, loading: Boolean): Boolean =
    editing != null && editing != createKey && entry == null && !loading

/** 应用中心插件瓦片 → 插件页路由（与 ToolRouter 并列的组合级覆盖分支） */
@Composable
fun PluginRouter(pluginId: String, deviceId: String?, onBack: () -> Unit) {
    when (pluginId) {
        "arpbind" -> ArpbindScreen(deviceId, onBack)
        "autoreboot" -> AutorebootScreen(deviceId, onBack)
        "cifs" -> CifsScreen(deviceId, onBack)
        "firewall" -> FirewallScreen(deviceId, onBack)
        "samba4" -> SambaScreen(deviceId, onBack)
        "upnp" -> UpnpScreen(deviceId, onBack)
        "usb-printer" -> UsbPrinterScreen(deviceId, onBack)
        "wolultra" -> WolScreen(deviceId, onBack)
    }
}

/** id 是否为已实装插件页（应用中心点击分流用） */
fun isPluginId(id: String): Boolean = AppRegistry.plugins.any { it.id == id }

/** 插件覆盖页骨架：返回头 + 副标题（插件 desc，UPnP 无副标题传 null）+ 下拉刷新 + 内容 */
@Composable
fun PluginPage(
    title: String,
    subtitleRes: Int?,
    onBack: () -> Unit,
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    // 覆盖页必须自带 BackHandler，否则系统返回直接退桌面
    androidx.activity.compose.BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
            }
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        if (subtitleRes != null) {
            Text(
                stringResource(subtitleRes),
                Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onRefresh != null) {
            androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(Modifier.fillMaxSize()) { content() }
            }
        } else {
            Box(Modifier.fillMaxSize()) { content() }
        }
    }
}

/** 分区标题 + 添加动作（列表页各区块的标准头） */
@Composable
internal fun PluginAddHead(title: String, actionText: String?, onAdd: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        if (actionText != null && onAdd != null) {
            TextButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(actionText)
            }
        }
    }
}

/** 行内开关（busy 时转圈替代，防重复写） */
@Composable
internal fun PluginBusySwitch(checked: Boolean, busy: Boolean, onChange: (Boolean) -> Unit) {
    if (busy) {
        CircularProgressIndicator(Modifier.size(30.dp).padding(4.dp), strokeWidth = 2.dp)
    } else {
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 插件确认弹窗（高危红字仅 firewall；删除确认复用同款） */
@Composable
fun PluginConfirmDialog(
    title: String,
    body: String,
    confirmText: String,
    highRisk: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (highRisk) {
                    Text(
                        stringResource(R.string.plugin_risk_warning),
                        Modifier.padding(bottom = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = if (highRisk) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) { Text(confirmText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.plugin_cancel)) } },
    )
}

/** 通用空态/提示行 */
@Composable
internal fun PluginEmptyHint(text: String) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Text(
            text,
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** 一次性写失败提示（VM 置 res → Toast → consume；错误链只进 logcat，UI 只显分类文案） */
@Composable
internal fun PluginWriteErrorEffect(writeErrorRes: Int?, onConsume: () -> Unit) {
    val context = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(writeErrorRes) {
        writeErrorRes?.let {
            Toast.makeText(context, context.getString(it), Toast.LENGTH_SHORT).show()
            onConsume()
        }
    }
}

/** 一次性操作成功提示（保存/删除成功 toast，与 LuCI 行为一致 save_success/delete_success） */
@Composable
internal fun PluginDoneEventEffect(eventRes: Int?, onConsume: () -> Unit) {
    val context = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(eventRes) {
        eventRes?.let {
            Toast.makeText(context, context.getString(it), Toast.LENGTH_SHORT).show()
            onConsume()
        }
    }
}

// ── schema 编辑页（全屏覆盖；草稿 rememberSaveable 跨往返/重建） ──

private const val DRAFT_LIST_SEP = "\u0001"

/** 草稿 Saver：标量 map + 列表 map（列表值 join 分隔符压平，Bundle 安全） */
private val PluginDraftSaver = listSaver<UciEditorDraft, Any>(
    save = { draft ->
        ArrayList<Any>().apply {
            add(ArrayList(draft.scalars.keys))
            add(ArrayList(draft.scalars.values))
            add(ArrayList(draft.lists.keys))
            add(ArrayList(draft.lists.values.map { it.joinToString(DRAFT_LIST_SEP) }))
        }
    },
    restore = { flat ->
        @Suppress("UNCHECKED_CAST")
        UciEditorDraft(
            scalars = (flat[0] as ArrayList<String>).zip(flat[1] as ArrayList<String>).toMap(),
            lists = (flat[2] as ArrayList<String>)
                .zip(flat[3] as ArrayList<String>) { k, joined -> k to joined.split(DRAFT_LIST_SEP) }
                .toMap(),
        )
    },
)

/**
 * schema 驱动编辑页：depends 未满足字段隐藏（不校验不提交在 buildValues 内对齐）；
 * 底栏 删除(可编辑且允许)｜取消｜保存；保存 = 本地校验 → 确认弹窗（高危红字）→ 上层写序列。
 * entry=null 为新建（草稿取 schema 默认值）。
 */
@Composable
fun UciEditPage(
    title: String,
    specs: List<UciFieldSpec>,
    entry: UciEntry?,
    candidates: Map<String, List<UciOption>>,
    allowDelete: Boolean,
    highRisk: Boolean,
    saving: Boolean,
    onDelete: (() -> Unit)?,
    onCancel: () -> Unit,
    onSave: (values: JSONObject) -> Unit,
) {
    var draft by rememberSaveable(stateSaver = PluginDraftSaver) {
        mutableStateOf(
            entry?.let { UciEditorLogic.draftFromEntry(specs, it) } ?: UciEditorLogic.initialDraft(specs),
        )
    }
    val context = LocalContext.current
    var confirmOpen by remember { mutableStateOf(false) }
    ToolPage(title = title, onBack = onCancel) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            ) {
                val visible = specs.filter { UciEditorLogic.dependsMet(it, draft) }
                var lastGroup: Int? = null
                visible.forEach { spec ->
                    if (spec.groupRes != null && spec.groupRes != lastGroup) {
                        Text(
                            stringResource(spec.groupRes),
                            Modifier.padding(top = 14.dp, bottom = 6.dp, start = 2.dp),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    lastGroup = spec.groupRes
                    PluginField(
                        spec = spec,
                        draft = draft,
                        candidates = candidates,
                        editingExisting = entry != null,
                        onScalar = { key, value -> draft = draft.copy(scalars = draft.scalars + (key to value)) },
                        onList = { key, value -> draft = draft.copy(lists = draft.lists + (key to value)) },
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onDelete != null && allowDelete && entry != null) {
                    OutlinedButton(
                        onClick = onDelete,
                        enabled = !saving,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
                OutlinedButton(onClick = onCancel, enabled = !saving, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.plugin_cancel))
                }
                Button(
                    onClick = {
                        val invalid = UciEditorLogic.firstInvalid(specs, draft)
                        if (invalid != null) {
                            val label = context.getString(invalid.labelRes)
                            // 语义分支：空值=必填缺失，非空=格式错误（与 LuCI 校验顺序一致）
                            val isEmpty = if (invalid.type == UciFieldType.DYNAMIC_LIST) {
                                draft.lists[invalid.key].isNullOrEmpty()
                            } else {
                                draft.scalars[invalid.key].isNullOrEmpty()
                            }
                            val msg = when {
                                isEmpty -> context.getString(R.string.plugin_required, label)
                                invalid.patternErrorRes != null -> context.getString(invalid.patternErrorRes)
                                else -> context.getString(R.string.plugin_invalid, label)
                            }
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        } else {
                            confirmOpen = true
                        }
                    },
                    enabled = !saving,
                    modifier = Modifier.weight(2f),
                ) {
                    if (saving) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.plugin_save))
                    }
                }
            }
        }
    }
    if (confirmOpen) {
        PluginConfirmDialog(
            title = stringResource(R.string.plugin_save),
            body = stringResource(R.string.plugin_save_confirm),
            confirmText = stringResource(R.string.plugin_save),
            highRisk = highRisk,
            onConfirm = {
                confirmOpen = false
                onSave(UciEditorLogic.buildValues(specs, draft))
            },
            onDismiss = { confirmOpen = false },
        )
    }
}

/**
 * 字段静态选项优先，否则取候选表；两者并存时合并（按 value 去重，静态在前——
 * usb-printer bind 的「所有接口 0.0.0.0」+ 接口 IP 列表）。
 * 与 LuCI uci 列表组件语义一致：`f.candidates === 'devices' || f.type === 'deviceSelect'` ——
 * **deviceSelect 类型隐式使用 devices 候选**（arpbind ifname / wol maceth 未显式声明
 * candidates，正是「无可用选项」的真根因）。
 */
private fun fieldOptions(spec: UciFieldSpec, candidates: Map<String, List<UciOption>>): List<UciOption> {
    val dynamic = when {
        spec.candidates != null -> candidates[spec.candidates.kind].orEmpty()
        spec.type == UciFieldType.DEVICE_SELECT -> candidates[UciCandidates.DEVICES.kind].orEmpty()
        else -> emptyList()
    }
    if (spec.options.isEmpty()) return dynamic
    if (dynamic.isEmpty()) return spec.options
    val seen = spec.options.map { it.value }.toHashSet()
    return spec.options + dynamic.filter { it.value !in seen }
}

@Composable
private fun optionLabel(opt: UciOption): String =
    opt.labelRes?.let { stringResource(it) } ?: opt.label ?: opt.value

@Composable
private fun PluginField(
    spec: UciFieldSpec,
    draft: UciEditorDraft,
    candidates: Map<String, List<UciOption>>,
    editingExisting: Boolean,
    onScalar: (String, String) -> Unit,
    onList: (String, List<String>) -> Unit,
) {
    val label = stringResource(spec.labelRes)
    when (spec.type) {
        UciFieldType.SWITCH -> Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = UciEditorLogic.switchOn(spec, draft),
                onCheckedChange = { on -> onScalar(spec.key, if (on) spec.onValue else spec.offValue) },
            )
        }
        UciFieldType.MULTI_SELECT -> {
            PluginFieldLabel(label, spec.required)
            val selected = draft.scalars[spec.key].orEmpty().split(' ').filter { it.isNotEmpty() }
            val opts = fieldOptions(spec, candidates)
            FlowRow(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 已选的自定义值不在候选集内也要可见可删（如协议号 47）
                selected.filter { value -> opts.none { it.value == value } }.forEach { extra ->
                    FilterChip(
                        selected = true,
                        onClick = {
                            onScalar(spec.key, (selected - extra).joinToString(" "))
                        },
                        label = { Text(extra) },
                    )
                }
                opts.forEach { opt ->
                    val optLabel = optionLabel(opt)
                    FilterChip(
                        selected = opt.value in selected,
                        onClick = {
                            val next = if (opt.value in selected) selected - opt.value else selected + opt.value
                            onScalar(spec.key, next.joinToString(" "))
                        },
                        label = { Text(optLabel) },
                    )
                }
            }
            if (spec.allowCustom) {
                MultiSelectCustomInput(selected) { next ->
                    onScalar(spec.key, next.joinToString(" "))
                }
            }
            FieldHint(spec)
        }
        UciFieldType.DYNAMIC_LIST -> DynamicListField(spec, draft.lists[spec.key].orEmpty(), onList)
        UciFieldType.SELECT, UciFieldType.DEVICE_SELECT -> SelectField(spec, candidates, draft) {
            onScalar(spec.key, it)
        }
        else -> {
            PluginFieldLabel(label, spec.required)
            val opts = fieldOptions(spec, candidates)
            var open by remember { mutableStateOf(false) }
            val lockName = spec.readOnly && editingExisting
            OutlinedTextField(
                value = draft.scalars[spec.key].orEmpty(),
                onValueChange = { onScalar(spec.key, it) },
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                placeholder = {
                    val ph = spec.placeholder
                    if (ph != null) {
                        Text(ph)
                    }
                },
                singleLine = true,
                readOnly = lockName,
                enabled = !lockName,
                supportingText = when {
                    lockName -> {
                        { Text(stringResource(R.string.plugin_name_readonly)) }
                    }
                    spec.hintRes != null -> {
                        { Text(stringResource(spec.hintRes)) }
                    }
                    else -> null
                },
                visualTransformation = if (spec.type == UciFieldType.PASSWORD) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                trailingIcon = if (opts.isNotEmpty()) {
                    {
                        IconButton(onClick = { open = true }) {
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                        }
                    }
                } else {
                    null
                },
            )
            PluginDropdown(open, { open = false }, opts) { onScalar(spec.key, it.value) }
        }
    }
}

/** select/deviceSelect 下拉字段（候选为空时显示提示行，避免「空菜单点不动」的表象） */
@Composable
private fun SelectField(
    spec: UciFieldSpec,
    candidates: Map<String, List<UciOption>>,
    draft: UciEditorDraft,
    onPick: (String) -> Unit,
) {
    PluginFieldLabel(stringResource(spec.labelRes), spec.required)
    val opts = fieldOptions(spec, candidates)
    val current = draft.scalars[spec.key].orEmpty()
    val display = opts.firstOrNull { it.value == current }?.let { optionLabel(it) }
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { open = true },
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    display ?: stringResource(R.string.plugin_please_select),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (display == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
        }
        PluginDropdown(open, { open = false }, opts) { onPick(it.value) }
    }
    FieldHint(spec)
    if (opts.isEmpty()) {
        // 候选为空时给可视反馈：空菜单肉眼不可见，表现为「点不动」
        Text(
            stringResource(R.string.plugin_no_options),
            Modifier.padding(top = 2.dp, bottom = 8.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Spacer(Modifier.height(10.dp))
    }
}

/** 字段说明文案（hintRes；SELECT/MULTI_SELECT 分支通用，TEXT 走 supportingText） */
@Composable
private fun FieldHint(spec: UciFieldSpec) {
    if (spec.hintRes != null) {
        Text(
            stringResource(spec.hintRes),
            Modifier.padding(top = 2.dp, bottom = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 自定义值输入（对齐 LuCI MultiValue create：如自定义协议号 47）；回车/加号追加，去重 */
@Composable
private fun MultiSelectCustomInput(selected: List<String>, onAdd: (List<String>) -> Unit) {
    var customInput by remember { mutableStateOf("") }
    OutlinedTextField(
        value = customInput,
        onValueChange = { customInput = it },
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        placeholder = { Text(stringResource(R.string.plugin_custom_add)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        trailingIcon = {
            IconButton(
                onClick = {
                    val v = customInput.trim()
                    if (v.isNotEmpty() && v !in selected) {
                        onAdd(selected + v)
                        customInput = ""
                    }
                },
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.plugin_list_add))
            }
        },
    )
}

@Composable
private fun PluginFieldLabel(label: String, required: Boolean) {
    Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (required) {
            Text(" *", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun PluginDropdown(
    open: Boolean,
    onDismiss: () -> Unit,
    opts: List<UciOption>,
    onPick: (UciOption) -> Unit,
) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        opts.forEach { opt ->
            DropdownMenuItem(
                text = { Text(optionLabel(opt)) },
                onClick = {
                    onDismiss()
                    onPick(opt)
                },
            )
        }
    }
}

/** 动态标签列表（与 LuCI dynamicList 一致：逐条添加、逐条删除；空列表不写=无法清空已存值，rpcd 限制） */
@Composable
private fun DynamicListField(
    spec: UciFieldSpec,
    values: List<String>,
    onList: (String, List<String>) -> Unit,
) {
    PluginFieldLabel(stringResource(spec.labelRes), spec.required)
    var input by remember { mutableStateOf("") }
    if (values.isNotEmpty()) {
        FlowRow(
            Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            values.forEachIndexed { index, value ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Row(
                        Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(value, style = MaterialTheme.typography.labelMedium)
                        IconButton(
                            onClick = { onList(spec.key, values.toMutableList().apply { removeAt(index) }) },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
    }
    OutlinedTextField(
        value = input,
        onValueChange = { input = it },
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        placeholder = {
            val ph = spec.placeholder
            if (ph != null) Text(ph) else Text(stringResource(R.string.plugin_list_add))
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        trailingIcon = {
            IconButton(
                onClick = {
                    val v = input.trim()
                    if (v.isNotEmpty()) {
                        onList(spec.key, values + v)
                        input = ""
                    }
                },
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.plugin_list_add))
            }
        },
    )
}
