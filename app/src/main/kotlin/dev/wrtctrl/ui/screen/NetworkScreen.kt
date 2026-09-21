@file:Suppress("TooManyFunctions")
// TooManyFunctions：网络页单屏聚合三区块（接口/无线/系统设备）+ wifi 编辑覆盖分支与弹窗组件，
// 拆文件只为过阈值伤内聚（PluginCommon/FirewallScreen 同款豁免）。

package dev.wrtctrl.ui.screen

import androidx.compose.animation.AnimatedVisibility
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wrtctrl.R
import dev.wrtctrl.ui.component.BadgeTone
import dev.wrtctrl.ui.component.CopyableRow
import dev.wrtctrl.ui.component.GroupHeader
import dev.wrtctrl.ui.component.InfoRow
import dev.wrtctrl.ui.component.PollingGate
import dev.wrtctrl.ui.component.QrCodeImage
import dev.wrtctrl.ui.component.SignalBars
import dev.wrtctrl.ui.component.StatusBadge
import dev.wrtctrl.ui.component.signalLevel
import dev.wrtctrl.ui.theme.ChartColors
import dev.wrtctrl.util.Format
import dev.wrtctrl.util.WifiQr
import dev.wrtctrl.viewmodel.IfaceInfo
import dev.wrtctrl.viewmodel.NetDeviceInfo
import dev.wrtctrl.viewmodel.NetworkParsers
import dev.wrtctrl.viewmodel.NetworkUiState
import dev.wrtctrl.viewmodel.NetworkViewModel
import dev.wrtctrl.viewmodel.RadioInfo
import dev.wrtctrl.viewmodel.WifiIface
import dev.wrtctrl.viewmodel.WifiSecret

/** 网络页（三 Tab 合一屏）：接口（IP 大字 + UP/DOWN 徽章 + wan 实时速率 +
 *  开机累计）/ 无线（radio 组条 + SSID 卡，分组改版）/ 系统设备（承载关系）。
 *  可见时 3s 静默轮询全量；wifi 编辑覆盖页打开期间轮询暂停。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(vm: NetworkViewModel, deviceId: String?, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(deviceId) { vm.ensureLoaded(deviceId) }
    // showQrFor = 二维码弹窗的运行时 ifname（已移除认证环节，点动作直接拉凭据显码）
    var showQrFor by remember { mutableStateOf<String?>(null) }
    // wifi 编辑覆盖页：值 = wireless 的 uci section 名（radio 名或 iface 段名）；saveable 跨重建保留
    var wifiEditor by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmToggleFor by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmToggleOn by rememberSaveable { mutableStateOf(false) }
    var confirmRestartFor by rememberSaveable { mutableStateOf<String?>(null) }
    val wifiHolder = rememberSaveableStateHolder()
    val editorTarget = wifiEditor
    if (editorTarget != null) {
        WifiEditorCover(
            target = editorTarget,
            deviceId = deviceId,
            modifier = modifier,
            holder = wifiHolder,
            onSaved = { vm.refreshWireless() },
            onBack = { wifiEditor = null },
        )
        return
    }
    // 主内容分支同样包 provider：进编辑覆盖页时整块离开组合，
    // holder 存下 LazyColumn 滚动位等 saveable 状态，返回原样恢复；未包 provider 则直接丢弃
    wifiHolder.SaveableStateProvider("network_main") {
        // 可见才轮询：组合级可见（底部 Tab 选中）× 生命周期双门控（共享 PollingGate）；
        // 编辑覆盖页打开时本块离开组合 → onDispose 关停轮询（编辑页不轮询）
        PollingGate(onActiveChange = vm::setPollingActive)
    PluginWriteErrorEffect(state.opEventRes) { vm.consumeOpEvent() }
    showQrFor?.let { ifname ->
        WifiQrDialog(
            ifname,
            state.wifiSecrets[ifname],
            errorText = state.wifiSecretErrors[ifname],
            onRetry = { vm.fetchWifiSecret(ifname) },
        ) { showQrFor = null }
    }
    RadioActionDialogs(
        toggleFor = confirmToggleFor,
        toggleOn = confirmToggleOn,
        restartFor = confirmRestartFor,
        onToggle = { name, on ->
            confirmToggleFor = null
            confirmRestartFor = null
            vm.setRadioEnabled(name, on)
        },
        onRestart = { name ->
            confirmToggleFor = null
            confirmRestartFor = null
            vm.restartRadio(name)
        },
        onDismiss = {
            confirmToggleFor = null
            confirmRestartFor = null
        },
    )
    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = vm::refresh,
        modifier = modifier.fillMaxSize(),
    ) {
        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (state.ifaces.isEmpty() && state.loadFailed) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.common_load_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
                } else {
                    val deviceByName = remember(state.deviceGroups) {
                        state.deviceGroups.flatMap { it.devices }.associateBy { it.name }
                    }
                    val chipByIfname = remember(state.radios) {
                        val map = mutableMapOf<String, String?>()
                        state.radios.forEach { radio ->
                            radio.ifaces.forEach { iface -> map[iface.ifname] = radio.chip }
                        }
                        map
                    }
                    // 未拉取凭据则先拉一次（uci wireless），随即开二维码弹窗
                    fun requestWifi(ifname: String) {
                        if (!state.wifiSecrets.containsKey(ifname)) vm.fetchWifiSecret(ifname)
                        showQrFor = ifname
                    }
                    LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { GroupHeader(stringResource(R.string.network_interfaces)) }
                items(state.ifaces, key = { it.name }) {
                    IfaceCard(it, state.wanRates, deviceByName[it.l3Device])
                }
                item { GroupHeader(stringResource(R.string.network_wireless), Modifier.padding(top = 8.dp)) }
                when {
                    !state.wirelessLoaded && !state.wirelessFailed ->
                        item { SectionText(stringResource(R.string.network_wireless_loading)) }
                    state.radios.isEmpty() && state.wirelessFailed ->
                        item { SectionText(stringResource(R.string.common_load_failed)) }
                    state.radios.isEmpty() ->
                        item { SectionText(stringResource(R.string.wifi_no_radio)) }
                    // 分组改版：radio 组条（启停/重启/详情）+ 其下 SSID 卡；空 radio 组也显示组条
                    else -> wirelessItems(
                        state = state,
                        requestWifi = { requestWifi(it) },
                        onAskToggle = { name, on ->
                            confirmToggleFor = name
                            confirmToggleOn = on
                        },
                        onAskRestart = { confirmRestartFor = it },
                        onOpenEditor = { wifiEditor = it },
                    )
                }
                item { GroupHeader(stringResource(R.string.network_sys_devices), Modifier.padding(top = 8.dp)) }
                val sysDevices = state.deviceGroups.filter { it.type != "bridge" }.flatMap { it.devices }
                if (sysDevices.isEmpty()) {
                    item { SectionText(stringResource(R.string.network_empty)) }
                } else {
                    items(sysDevices, key = { "dev_${it.name}" }) { device ->
                        DeviceRow(
                            device,
                            carrying = carryingLabel(device, state),
                            chip = chipByIfname[device.name],
                        )
                    }
                }
            }
        }
    }
    }
}

/** 无线分组条目：radio 组条（启停/重启/详情）+ 其下 SSID 卡 */
private fun androidx.compose.foundation.lazy.LazyListScope.wirelessItems(
    state: NetworkUiState,
    requestWifi: (String) -> Unit,
    onAskToggle: (String, Boolean) -> Unit,
    onAskRestart: (String) -> Unit,
    onOpenEditor: (String) -> Unit,
) {
    state.radios.forEach { radio ->
        item(key = "radio_${radio.name}") {
            RadioBar(
                radio = radio,
                busy = state.busyRadio == radio.name,
                onToggle = { on -> onAskToggle(radio.name, on) },
                onRestart = { onAskRestart(radio.name) },
                onEdit = { onOpenEditor(radio.name) },
            )
        }
        radio.ifaces.forEach { iface ->
            // key 不能用裸 ifname：radio 关停后运行时名变空串，多个空串 key 直接崩
            // ；section 全局唯一（uci 段名），缺省回落 radio 名前缀
            item(key = iface.section ?: "iface_${radio.name}_${iface.ifname}") {
                SsidCard(
                    radio = radio,
                    iface = iface,
                    assocCount = state.assocCounts[iface.ifname],
                    secret = state.wifiSecrets[iface.ifname],
                    onRequestWifi = { requestWifi(iface.ifname) },
                    // 段名缺失（个别固件 status 不带 section）→ 不显编辑入口
                    onEdit = iface.section?.let { section -> { onOpenEditor(section) } },
                )
            }
        }
    }
}

/** wifi 编辑覆盖分支：SaveableStateProvider 按 deviceId|target 保留（切设备后旧段草稿不复活） */
@Composable
private fun WifiEditorCover(
    target: String,
    deviceId: String?,
    modifier: Modifier,
    holder: androidx.compose.runtime.saveable.SaveableStateHolder,
    onSaved: () -> Unit,
    onBack: () -> Unit,
) {
    Box(modifier) {
        holder.SaveableStateProvider("wifi_edit_${deviceId}_$target") {
            WifiEditorScreen(
                target = target,
                deviceId = deviceId,
                onSaved = onSaved,
                onBack = onBack,
            )
        }
    }
}

/** radio 启停/重启的高危确认弹窗（wireless 高危红字；文案覆盖断连预期） */
@Composable
private fun RadioActionDialogs(
    toggleFor: String?,
    toggleOn: Boolean,
    restartFor: String?,
    onToggle: (String, Boolean) -> Unit,
    onRestart: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    toggleFor?.let { radioName ->
        PluginConfirmDialog(
            title = stringResource(if (toggleOn) R.string.wifi_radio_on_title else R.string.wifi_radio_off_title),
            body = stringResource(R.string.wifi_radio_toggle_body),
            confirmText = stringResource(R.string.wifi_toggle_ok),
            highRisk = true,
            onConfirm = { onToggle(radioName, toggleOn) },
            onDismiss = onDismiss,
        )
    }
    restartFor?.let { radioName ->
        PluginConfirmDialog(
            title = stringResource(R.string.wifi_restart_title),
            body = stringResource(R.string.wifi_radio_toggle_body),
            confirmText = stringResource(R.string.wifi_toggle_ok),
            highRisk = true,
            onConfirm = { onRestart(radioName) },
            onDismiss = onDismiss,
        )
    }
}

/** 设备的承载关系：l3_device 命中 → 承载接口名；无线 ifname 命中 → 承载 SSID */
private fun carryingLabel(device: NetDeviceInfo, state: NetworkUiState): String? {
    state.ifaces.firstOrNull { it.l3Device == device.name }?.let { return it.name }
    state.radios.flatMap { it.ifaces }.firstOrNull { it.ifname == device.name }?.let { return it.ssid }
    return null
}

/** 接口卡：卡头（名称·协议 / 主值 / 状态徽章 / chevron，整行点击展开）
 *  + 网关/DNS 值条 + 摘要行（wan 实时速率 + 开机累计，灰）；
 *  MAC/MTU/桥接端口/IPv4/IPv6/PD 收进展开区。wan 主卡默认展开（唯一有实时速率）。 */
@Composable
private fun IfaceCard(
    iface: IfaceInfo,
    wanRates: Pair<Long, Long>?,
    device: NetDeviceInfo?,
) {
    var showV6Dialog by remember { mutableStateOf(false) }
    var showPdDialog by remember { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(iface.name == "wan") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            IfaceHeader(iface, expanded) { expanded = !expanded }
            IfaceRatesRow(iface, wanRates)
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = 6.dp)) {
                    iface.mac?.let { CopyableRow(stringResource(R.string.network_mac), it) }
                    device?.mtu?.let { InfoRow(stringResource(R.string.network_mtu), it.toString()) }
                    if (device?.ports?.isNotEmpty() == true) {
                        InfoRow(stringResource(R.string.network_bridge_ports), device.ports.joinToString(", "))
                    }
                    iface.ipv4?.let { CopyableRow(stringResource(R.string.network_ipv4), it) }
                    IfaceV6Rows(
                        iface,
                        onShowV6 = { showV6Dialog = true },
                        onShowPd = { showPdDialog = true },
                    )
                }
            }
        }
    }
    if (showV6Dialog) {
        V6DetailDialog(
            title = stringResource(R.string.network_ipv6),
            sections = listOf(
                stringResource(R.string.network_ipv6) to iface.ipv6Addrs,
                stringResource(R.string.network_ipv6_pd) to iface.ipv6Prefix,
            ).filter { it.second.isNotEmpty() },
            onDismiss = { showV6Dialog = false },
        )
    }
    if (showPdDialog) {
        V6DetailDialog(
            title = stringResource(R.string.network_ipv6_pd_assign),
            sections = listOf(stringResource(R.string.network_ipv6_pd_assign) to iface.ipv6PdAssign),
            onDismiss = { showPdDialog = false },
        )
    }
}

/** 卡头：名称·协议 label + 主值 + UP/DOWN 徽章 + chevron，整行点击展开/收起。
 *  主值：DOWN「未连接」置灰；UP 有 IPv4 显 IPv4，仅 IPv6 显「仅 IPv6」，无地址显「无地址」
 *  （需同时确认 IPv4 或 IPv6 任一存在，IPv6-only 接口不被误标「未连接」）。
 *  网关/DNS 值条属摘要，始终可见。 */
@Composable
private fun IfaceHeader(iface: IfaceInfo, expanded: Boolean, onToggle: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    listOfNotNull(iface.name, iface.proto?.lowercase()).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val mainValue = when {
                    !iface.up -> stringResource(R.string.network_not_connected)
                    iface.ipv4 != null -> iface.ipv4
                    iface.ipv6Addrs.isNotEmpty() || iface.ipv6Prefix.isNotEmpty() ->
                        stringResource(R.string.network_ipv6_only)
                    else -> stringResource(R.string.network_no_address)
                }
                Text(
                    mainValue,
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (iface.up) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            StatusBadge(
                stringResource(if (iface.up) R.string.network_up else R.string.network_down),
                if (iface.up) BadgeTone.OK else BadgeTone.ERR,
            )
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                modifier = Modifier.padding(start = 8.dp).size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (iface.gateway != null || iface.dns.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                iface.gateway?.let { SubChip(stringResource(R.string.network_gateway), it) }
                iface.dns.firstOrNull()?.let { SubChip(stringResource(R.string.network_dns), it) }
            }
        }
    }
}

/** 速率行：wan 实时（↓↑ 单位独立换档）+ 开机累计（口径限定词） */
@Composable
private fun IfaceRatesRow(iface: IfaceInfo, wanRates: Pair<Long, Long>?) {
    val rates = wanRates?.takeIf { iface.name == "wan" }
    if (rates == null && iface.rxBytes <= 0 && iface.txBytes <= 0) return
    val dark = isSystemInDarkTheme()
    val rxText = if (dark) ChartColors.rxTextDark else ChartColors.rxTextLight
    val txText = if (dark) ChartColors.txTextDark else ChartColors.txTextLight
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (rates != null) {
            val rx = Format.rateParts(rates.first)
            val tx = Format.rateParts(rates.second)
            Text(
                "↓ ${rx.value} ${rx.unit}",
                style = MaterialTheme.typography.labelMedium,
                color = rxText,
            )
            Text(
                "↑ ${tx.value} ${tx.unit}",
                style = MaterialTheme.typography.labelMedium,
                color = txText,
            )
        }
        Text(
            stringResource(R.string.network_boot_total) +
                " ↓ ${Format.bytes(iface.rxBytes)} · ↑ ${Format.bytes(iface.txBytes)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** IPv6 / PD 行：首条 + 截断展示，超一条或超长出现眼睛进详情弹窗 */
@Composable
private fun IfaceV6Rows(iface: IfaceInfo, onShowV6: () -> Unit, onShowPd: () -> Unit) {
    if (iface.ipv6Addrs.isNotEmpty() || iface.ipv6Prefix.isNotEmpty()) {
        // 地址或委派前缀任一非空即显行；地址空时显示 -（眼睛仍可看 PD 分节）
        val eyeVisible = iface.ipv6Prefix.isNotEmpty() || iface.ipv6Addrs.size > 1
        val first = iface.ipv6Addrs.firstOrNull()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            CopyableRow(
                label = stringResource(R.string.network_ipv6),
                value = first ?: "-",
                modifier = Modifier.weight(1f),
                display = first?.let(::truncate30) ?: "-",
            )
            if (eyeVisible) {
                EyeButton(stringResource(R.string.network_ipv6)) { onShowV6() }
            }
        }
    }
    if (iface.ipv6PdAssign.isNotEmpty()) {
        val first = iface.ipv6PdAssign.first()
        val eyeVisible = iface.ipv6PdAssign.size > 1 || first.length > 30
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            CopyableRow(
                label = stringResource(R.string.network_ipv6_pd_assign),
                value = first,
                modifier = Modifier.weight(1f),
                display = truncate30(first),
            )
            if (eyeVisible) {
                EyeButton(stringResource(R.string.network_ipv6_pd_assign)) { onShowPd() }
            }
        }
    }
}

/** 网关/DNS 值条：横向滚动防溢出，浅底 chip 承载（纯展示；IPv4 等行保留复制） */
@Composable
private fun SubChip(label: String, value: String) {
    Text(
        "$label $value",
        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** SSID 卡：：SSID 行整行点击复制+ 加密/信道 + 信号档位 + 关联终端数 + 右侧二维码缩略（「放大」）；
 *  元信息行尾加「详情 →」编辑入口（段名缺失时 onEdit=null 不显示） */
@Composable
private fun SsidCard(
    radio: RadioInfo,
    iface: WifiIface,
    assocCount: Int?,
    secret: WifiSecret?,
    onRequestWifi: () -> Unit,
    onEdit: (() -> Unit)? = null,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    // radio 关停后运行时 ifname 与 iwinfo ssid 均为空——标题回退 uci section 名
    val title = iface.ssid?.takeIf { it.isNotBlank() }
        ?: iface.ifname.takeIf { it.isNotBlank() }
        ?: iface.section.orEmpty()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            // SSID 行整行点击复制（隐式复制惯例，同 CopyableRow 反馈）
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        clipboard.setText(AnnotatedString(title))
                        Toast.makeText(context, context.getString(R.string.common_copied), Toast.LENGTH_SHORT).show()
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 左侧内容 weight 填满贴右缘（不放占位 Spacer）
                val meta = buildList {
                    add(
                        NetworkParsers.encryptionLabel(
                            iface.encryption,
                            stringResource(R.string.network_no_encryption),
                            stringResource(R.string.network_encrypted),
                        ),
                    )
                    radio.channel?.let { add("Ch $it") }
                }.joinToString(" · ")
                Text(
                    meta,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onEdit != null) {
                    TextButton(onClick = onEdit, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text(stringResource(R.string.entry_details))
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    iface.signal?.let { signal ->
                        SignalBars(signal)
                        Text(
                            signalTierText(signal),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    assocCount?.let {
                        Text(
                            stringResource(R.string.network_assoc_online, it),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // 二维码缩略（「放大」）：已认证且个人网显示真码，否则入口图标；点击走认证流
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onRequestWifi),
                ) {
                    Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.size(54.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            val payload = secret
                                ?.takeIf { WifiQr.isPersonal(it.encryption) }
                                ?.let { WifiQr.payload(it.encryption, it.ssid ?: iface.ifname, it.key) }
                            if (payload != null) {
                                QrCodeImage(payload, Modifier.size(48.dp))
                            } else {
                                Icon(
                                    Icons.Filled.QrCode2,
                                    contentDescription = null,
                                    modifier = Modifier.size(30.dp),
                                    tint = Color(0xFF333333),
                                )
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.wifi_qr_zoom),
                        Modifier.padding(top = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            iface.bssid?.let { CopyableRow(stringResource(R.string.network_bssid), it) }
        }
    }
}

/** 系统设备行：名称 + 芯片/承载 sub 行 + UP 徽章（设备分组已滤 DOWN） */
@Composable
private fun DeviceRow(device: NetDeviceInfo, carrying: String?, chip: String?) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                val sub = listOfNotNull(chip, carrying?.let { stringResource(R.string.network_carrying, it) })
                if (sub.isNotEmpty()) {
                    Text(
                        sub.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            StatusBadge(
                stringResource(if (device.up) R.string.network_up else R.string.network_down),
                if (device.up) BadgeTone.OK else BadgeTone.ERR,
            )
        }
    }
}

@Composable
private fun signalTierText(dBm: Int): String = stringResource(
    when (signalLevel(dBm)) {
        4 -> R.string.signal_excellent
        3 -> R.string.signal_good
        2 -> R.string.signal_fair
        1 -> R.string.signal_weak
        else -> R.string.signal_poor
    },
)

@Composable
private fun SectionText(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
