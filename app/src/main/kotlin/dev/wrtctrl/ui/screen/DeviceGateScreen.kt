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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import dev.wrtctrl.R
import dev.wrtctrl.data.Device
import dev.wrtctrl.viewmodel.AppViewModel
import dev.wrtctrl.viewmodel.GateMode
import dev.wrtctrl.viewmodel.GateUiState

/** 设备门控页（双形态）：列表（直连/编辑/删除）+ 添加·编辑表单。*/
@Composable
fun DeviceGateScreen(vm: AppViewModel, onOpenLanguage: () -> Unit = {}) {
    val state by vm.gate.collectAsState()
    when (state.mode) {
        GateMode.List -> ListMode(vm, state, onOpenLanguage)
        GateMode.Form -> FormMode(vm, state, onOpenLanguage)
    }
}

/// 语言切换入口：固定在顶栏右上角（用户反馈：不应藏在表单底部）
@Composable
internal fun LanguageAction(onOpenLanguage: () -> Unit) {
    IconButton(onClick = onOpenLanguage) {
        Icon(
            Icons.Filled.Language,
            contentDescription = stringResource(R.string.device_list_language_settings),
        )
    }
}

// ── 列表形态 ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListMode(vm: AppViewModel, state: GateUiState, onOpenLanguage: () -> Unit) {
    var deleting by remember { mutableStateOf<Device?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.device_list_history_title)) },
                actions = { LanguageAction(onOpenLanguage) },
            )
        },
        floatingActionButton = {
            if (state.devices.isNotEmpty()) {
                ExtendedAddButton(onClick = { vm.openForm(null) })
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.connecting) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            state.bannerError?.let { banner ->
                Card(
                    Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Text(
                        banner,
                        Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (state.devices.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.device_list_history_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Text(
                            stringResource(R.string.device_list_ping_hint),
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(state.devices, key = { it.id }) { device ->
                        DeviceCard(
                            device = device,
                            pingMs = state.pings[device.id],
                            isCurrent = vm.current.value?.id == device.id,
                            connecting = state.connecting,
                            onClick = { vm.connectTo(device) },
                            onEdit = { vm.openForm(device) },
                            onDelete = { deleting = device },
                        )
                    }
                }
            }
        }
    }

    deleting?.let { device ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.device_list_delete_confirm_title)) },
            text = { Text(stringResource(R.string.device_list_delete_confirm_content)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteDevice(device.id)
                    deleting = null
                }) { Text(stringResource(R.string.device_list_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceCard(
    device: Device,
    pingMs: Long?,
    isCurrent: Boolean,
    connecting: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(enabled = !connecting, onClick = onClick),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    device.displayName.trim().take(1).uppercase(),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(device.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${device.username} · ${device.displayAddress}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isCurrent) {
                Text(
                    stringResource(R.string.device_list_switch_current),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.size(8.dp))
            PingBadge(pingMs)
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.device_list_more_aria))
                }
                androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.device_list_edit)) },
                        leadingIcon = { Icon(Icons.Filled.Edit, null) },
                        onClick = { menuOpen = false; onEdit() },
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.device_list_delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun PingBadge(pingMs: Long?) {
    val (text, color) = when {
        pingMs == null -> stringResource(R.string.device_list_ping_offline) to MaterialTheme.colorScheme.error
        else -> "${pingMs}ms" to pingColor(pingMs)
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        textAlign = TextAlign.End,
    )
}

private fun pingColor(ms: Long): Color = when {
    ms < 100 -> Color(0xFF2E7D32)
    ms < 300 -> Color(0xFFB26A00)
    else -> Color(0xFFC62828)
}

@Composable
private fun ExtendedAddButton(onClick: () -> Unit) {
    androidx.compose.material3.ExtendedFloatingActionButton(onClick = onClick) {
        Text(stringResource(R.string.device_list_add_new_device))
    }
}

// ── 表单形态 ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormMode(vm: AppViewModel, state: GateUiState, onOpenLanguage: () -> Unit) {
    val editing = state.editingId != null
    val hasDevices = state.devices.isNotEmpty()
    var showPassword by remember { mutableStateOf(false) }
    val passwordFocus = remember { FocusRequester() }
    val scroll = rememberScrollState()

    // 认证失败 → 自动聚焦密码框
    LaunchedEffect(state.formErrorCode) {
        if (state.formErrorCode == "auth") passwordFocus.requestFocus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (editing) R.string.device_list_edit_device else R.string.device_list_add_new_device
                        )
                    )
                },
                navigationIcon = {
                    if (hasDevices || editing) {
                        IconButton(onClick = { vm.backToList() }) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.common_cancel))
                        }
                    }
                },
                actions = { LanguageAction(onOpenLanguage) },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionLabel(stringResource(R.string.device_list_group_connection))
            OutlinedTextField(
                value = state.form.host,
                onValueChange = { v -> vm.updateForm { it.copy(host = v) } },
                label = { Text(stringResource(R.string.device_list_host_address)) },
                placeholder = { Text(stringResource(R.string.device_list_host_placeholder)) },
                singleLine = true,
                isError = state.fieldErrors.containsKey("host"),
                supportingText = { FieldError(state.fieldErrors["host"]) },
                modifier = Modifier.fillMaxWidth(),
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !state.form.useHttps,
                    onClick = { vm.setUseHttps(false) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text(stringResource(R.string.device_list_protocol_http)) }
                SegmentedButton(
                    selected = state.form.useHttps,
                    onClick = { vm.setUseHttps(true) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text(stringResource(R.string.device_list_protocol_https)) }
            }
            OutlinedTextField(
                value = state.form.port,
                onValueChange = { v -> vm.updateForm { it.copy(port = v.filter(Char::isDigit)) } },
                label = { Text(stringResource(R.string.device_list_port)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                isError = state.fieldErrors.containsKey("port"),
                supportingText = { FieldError(state.fieldErrors["port"]) },
                modifier = Modifier.fillMaxWidth(),
            )

            SectionLabel(stringResource(R.string.device_list_group_credentials))
            OutlinedTextField(
                value = state.form.username,
                onValueChange = { v -> vm.updateForm { it.copy(username = v) } },
                label = { Text(stringResource(R.string.device_list_username)) },
                placeholder = { Text(stringResource(R.string.device_list_username_placeholder)) },
                singleLine = true,
                isError = state.fieldErrors.containsKey("username"),
                supportingText = { FieldError(state.fieldErrors["username"]) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.form.password,
                onValueChange = { v -> vm.updateForm { it.copy(password = v) } },
                label = { Text(stringResource(R.string.device_list_password)) },
                placeholder = { Text(stringResource(R.string.device_list_password_placeholder)) },
                singleLine = true,
                isError = state.fieldErrors.containsKey("password"),
                supportingText = { FieldError(state.fieldErrors["password"]) },
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = stringResource(
                                if (showPassword) R.string.device_list_password_hide else R.string.device_list_password_show
                            ),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().focusRequester(passwordFocus),
            )

            SectionLabel(stringResource(R.string.device_list_group_naming))
            OutlinedTextField(
                value = state.form.name,
                onValueChange = { v -> vm.updateForm { it.copy(name = v) } },
                label = { Text(stringResource(R.string.device_list_remark)) },
                placeholder = { Text(stringResource(R.string.device_list_name_placeholder)) },
                singleLine = true,
                isError = state.fieldErrors.containsKey("name"),
                supportingText = { FieldError(state.fieldErrors["name"]) },
                modifier = Modifier.fillMaxWidth(),
            )

            state.formErrorText?.let { errorText ->
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Text(
                        errorText,
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            Button(
                onClick = { vm.submit() },
                enabled = !state.connecting,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                if (state.connecting) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.device_list_connect))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun FieldError(resId: Int?) {
    resId?.let { Text(stringResource(it)) }
}
