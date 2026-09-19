package dev.wrtctrl.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wrtctrl.R
import dev.wrtctrl.ui.component.Badge
import dev.wrtctrl.ui.component.CopyableRow
import dev.wrtctrl.ui.component.InfoRow
import dev.wrtctrl.util.Format
import dev.wrtctrl.viewmodel.DeviceGroup
import dev.wrtctrl.viewmodel.IfaceInfo
import dev.wrtctrl.viewmodel.NetDeviceInfo
import dev.wrtctrl.viewmodel.NetworkParsers
import dev.wrtctrl.viewmodel.NetworkUiState
import dev.wrtctrl.viewmodel.NetworkViewModel
import dev.wrtctrl.viewmodel.RadioInfo
import dev.wrtctrl.viewmodel.WifiIface

/** 网络页：接口 / 设备 / 无线三视角。无轮询，进页/下拉刷新拉取。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(vm: NetworkViewModel, deviceId: String?, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(deviceId) { vm.ensureLoaded(deviceId) }
    var tab by remember { mutableIntStateOf(0) }
    // 无线数据首进该 Tab 才拉（loadWireless 自带 wirelessLoaded 门）
    LaunchedEffect(tab) {
        if (tab == 2) vm.loadWireless()
    }
    Column(modifier) {
        val tabs = listOf(
            R.string.network_interfaces,
            R.string.network_devices,
            R.string.network_wireless,
        )
        TabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { index, res ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = { Text(stringResource(res)) },
                )
            }
        }
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            when (tab) {
                0 -> IfaceTab(state)
                1 -> DeviceTab(state)
                else -> WirelessTab(state)
            }
        }
    }
}

@Composable
private fun IfaceTab(state: NetworkUiState) {
    if (state.loading) {
        CenterContent(spinner = true)
    } else if (state.ifaces.isEmpty()) {
        CenterContent(stringResource(R.string.network_empty))
    } else {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.ifaces.size) { i -> IfaceCard(state.ifaces[i]) }
        }
    }
}

@Composable
private fun IfaceCard(iface: IfaceInfo) {
    var showV6Dialog by remember { mutableStateOf(false) }
    var showPdDialog by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    iface.name.uppercase() + (iface.l3Device?.let { " ($it)" } ?: ""),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                Badge(iface.proto ?: "-")
            }
            iface.mac?.let { CopyableRow(stringResource(R.string.network_mac), it) }
            InfoRow(
                stringResource(R.string.network_traffic_rx_tx),
                "${Format.bytes(iface.rxBytes)} / ${Format.bytes(iface.txBytes)}",
            )
            iface.ipv4?.let { CopyableRow(stringResource(R.string.network_ipv4), it) }
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
                        EyeButton { showV6Dialog = true }
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
                        EyeButton { showPdDialog = true }
                    }
                }
            }
            iface.gateway?.let { CopyableRow(stringResource(R.string.network_gateway), it) }
            iface.dns.forEach { dns ->
                CopyableRow(stringResource(R.string.network_dns), dns)
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

@Composable
private fun EyeButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        Icon(
            Icons.Filled.Visibility,
            contentDescription = stringResource(R.string.network_ipv6),
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** IPv6 / PD 详情弹窗：分节标题 + 每条可复制（长列表内滚动） */
@Composable
private fun V6DetailDialog(title: String, sections: List<Pair<String, List<String>>>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                sections.forEach { (section, items) ->
                    Text(
                        section,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    items.forEach { CopyableRow(section, it) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}

@Composable
private fun DeviceTab(state: NetworkUiState) {
    if (state.loading) {
        CenterContent(spinner = true)
    } else if (state.deviceGroups.isEmpty()) {
        CenterContent(stringResource(R.string.network_empty))
    } else {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.deviceGroups.forEach { group ->
                item(key = "header_${group.type}") { GroupHeader(group) }
                items(group.devices.size, key = { i -> "${group.type}_${group.devices[i].name}" }) { i ->
                    DeviceCard(group, group.devices[i])
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(group: DeviceGroup) {
    val label = stringResource(
        when (group.type) {
            "bridge" -> R.string.network_device_type_bridge
            "ethernet" -> R.string.network_device_type_ethernet
            "wireless" -> R.string.network_device_type_wireless
            "vlan" -> R.string.network_device_type_vlan
            "tunnel" -> R.string.network_device_type_tunnel
            else -> R.string.network_other
        },
    )
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(4.dp)
                .height(18.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(8.dp))
        Text("${label} (${group.devices.size})", style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun DeviceCard(group: DeviceGroup, device: NetDeviceInfo) {
    val packets = stringResource(R.string.network_packets)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(device.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Badge(
                    stringResource(if (device.up) R.string.network_up else R.string.network_down),
                    isError = !device.up,
                )
            }
            // MAC 可复制（绑定/过滤场景）；MTU/端口短枚举纯展示
            CopyableRow(stringResource(R.string.network_mac), device.mac ?: "-")
            if (group.type == "bridge" && device.ports.isNotEmpty()) {
                InfoRow(
                    stringResource(R.string.network_bridge_ports),
                    device.ports.joinToString(", "),
                )
            }
            InfoRow(stringResource(R.string.network_mtu), device.mtu?.toString() ?: "-")
            InfoRow(
                stringResource(R.string.network_receive),
                "${Format.bytes(device.rxBytes)} · ${Format.compactCount(device.rxPackets)} $packets",
            )
            InfoRow(
                stringResource(R.string.network_send),
                "${Format.bytes(device.txBytes)} · ${Format.compactCount(device.txPackets)} $packets",
            )
        }
    }
}

@Composable
private fun WirelessTab(state: NetworkUiState) {
    when {
        !state.wirelessLoaded -> CenterContent(stringResource(R.string.network_wireless_loading))
        state.radios.isEmpty() -> CenterContent(stringResource(R.string.wifi_no_radio))
        else -> LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.radios.size) { i -> RadioCard(state.radios[i]) }
        }
    }
}

@Composable
private fun RadioCard(radio: RadioInfo) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // radio 卡头显示原始名（radio0），不做大写
                Text(radio.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                // 设置入口目标为 wifi 页（M4/），M3 阶段保留占位不可用
                TextButton(onClick = {}, enabled = false) {
                    Text("${stringResource(R.string.device_list_settings)} ›")
                }
            }
            // 复制收敛：只有地址类与值得搜索的芯片名可复制，
            // 短枚举值（频段/信道/协议）纯展示——避免误触 toast 与无意义复制
            CopyableRow(stringResource(R.string.network_chip), radio.chip ?: "-")
            InfoRow(stringResource(R.string.network_band), radio.band ?: "-")
            InfoRow(stringResource(R.string.network_channel), radio.channel ?: "-")
            InfoRow(stringResource(R.string.network_protocol), "802.11${radio.hwmodes ?: "-"}")
            if (radio.ifaces.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                radio.ifaces.forEach { WifiIfaceCard(it) }
            }
        }
    }
}

@Composable
private fun WifiIfaceCard(iface: WifiIface) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "SSID: ${iface.ssid ?: "-"}",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                )
                // 模式恒显示（缺失补 -）
                Text(
                    "${stringResource(R.string.network_mode)}: ${iface.mode ?: "-"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CopyableRow(stringResource(R.string.network_bssid), iface.bssid ?: "-")
            InfoRow(stringResource(R.string.network_signal), "${iface.signal ?: "-"} dBm")
            iface.bitrate?.let { InfoRow(stringResource(R.string.network_bitrate), Format.bitrate(it)) }
            InfoRow(
                stringResource(R.string.network_encryption),
                NetworkParsers.encryptionLabel(
                    iface.encryption,
                    stringResource(R.string.network_no_encryption),
                    stringResource(R.string.network_encrypted),
                ),
            )
        }
    }
}

@Composable
private fun CenterContent(text: String? = null, spinner: Boolean = false) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            spinner -> CircularProgressIndicator()
            text != null -> Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** IPv6/PD 行显示值截断：超 30 字符截断加省略号（完整值进弹窗或点击复制） */
private fun truncate30(s: String): String = if (s.length > 30) s.take(30) + "…" else s
