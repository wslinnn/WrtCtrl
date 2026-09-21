@file:Suppress("TooManyFunctions")

package dev.wrtctrl.ui.screen

// 客户端页组件族（区块/行/动作行/确认弹窗）内聚于一页文件——「每页一个文件」惯例优先于函数数阈值

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wrtctrl.R
import dev.wrtctrl.ui.component.CopyableRow
import dev.wrtctrl.ui.component.GroupHeader
import dev.wrtctrl.ui.component.InfoRow
import dev.wrtctrl.ui.component.KpiValue
import dev.wrtctrl.ui.component.PollingGate
import dev.wrtctrl.ui.component.SignalBars
import dev.wrtctrl.ui.component.StatusBadge
import dev.wrtctrl.ui.component.badgeToneColor
import dev.wrtctrl.ui.component.BadgeTone
import dev.wrtctrl.util.Format
import dev.wrtctrl.viewmodel.ClientParsers
import dev.wrtctrl.viewmodel.ClientUiState
import dev.wrtctrl.viewmodel.ClientViewModel
import dev.wrtctrl.viewmodel.DhcpLease
import dev.wrtctrl.viewmodel.StaticHost
import dev.wrtctrl.viewmodel.WifiClient

/** 客户端页（两 Tab 合一屏，行做减法）：状态汇总卡（无线 N / 租约 N）+ 搜索 +
 *  无线客户端区（名称 + IP + 信号条，展开 MAC/时长/频段）+ DHCP 租约区（静态/动态行内标注）。
 *  可见时 3s 静默轮询全量。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientScreen(vm: ClientViewModel, deviceId: String?, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(deviceId) { vm.ensureLoaded(deviceId) }
    var search by rememberSaveable { mutableStateOf("") }
    // 写操作确认弹窗目标（分字段 saveable：数据类无 Saver；mac 非空 = 弹窗打开，其余为打开时快照）
    var pendingAction by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingMac by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingName by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingIp by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    // 写失败分类文案一次性呈现（原始错误链只在 logcat）
    LaunchedEffect(state.writeError) {
        state.writeError?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.consumeWriteError()
        }
    }
    // 可见才轮询：组合级可见（底部 Tab 选中）× 生命周期双门控（共享 PollingGate）
    PollingGate(onActiveChange = vm::setPollingActive)

    fun requestWrite(action: String, mac: String, name: String?, ip: String?) {
        pendingAction = action
        pendingMac = mac
        pendingName = name
        pendingIp = ip
    }

    Column(modifier) {
        SearchField(search, onSearch = { search = it })
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            val wireless = state.wirelessClients.matching(search) { listOf(it.mac, it.hostname, it.ip) }
            val leases4 = state.dhcpv4.matching(search) { listOf(it.macaddr, it.hostname, it.ip) }
            val leases6 = state.dhcpv6.matching(search) { listOf(it.macaddr, it.hostname, it.ip) }
            val staticMacSet = remember(state.staticHosts) { state.staticHosts.map { it.mac }.toSet() }
            // 已封禁区块：firewall block_ 规则全量，与客户端列表解耦——
            // 租约过期/断联的设备仍可解除；名称 = 动态租约名 → 静态租约名，查不到只显 MAC（数据有源）
            val blockedEntries = remember(state.blockedMacs, state.dhcpv4, state.dhcpv6, state.staticHosts) {
                val hostnames = ClientParsers.hostnameMap(state.dhcpv4, state.dhcpv6)
                val ips = ClientParsers.ipMap(state.dhcpv4)
                state.blockedMacs.keys
                    .map { mac ->
                        BlockedEntry(
                            mac = mac,
                            name = hostnames[mac.uppercase()]
                                ?: state.staticHosts.firstOrNull { it.mac == mac.uppercase() }?.name,
                            ip = ips[mac.uppercase()],
                        )
                    }
                    .sortedBy { it.mac }
            }
            val blockedFiltered = blockedEntries.matching(search) { listOf(it.name, it.ip, it.mac) }
            // 静态租约区块：uci dhcp @host 全量（含绑定 IP），离线绑定同样可查看/取消
            val staticEntries = remember(state.staticHosts) { state.staticHosts }
            val staticFiltered = staticEntries.matching(search) { listOf(it.name, it.ip, it.mac) }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { SummaryCard(state) }
                item { GroupHeader(stringResource(R.string.client_wireless_clients), Modifier.padding(top = 4.dp)) }
                wirelessSection(wireless, state, staticMacSet, search.isNotBlank(), ::requestWrite)
                item { GroupHeader(stringResource(R.string.client_dhcp_leases), Modifier.padding(top = 8.dp)) }
                leaseSection(leases4, leases6, state, staticMacSet, search.isNotBlank(), ::requestWrite)
                // 条件组：无封禁整组隐藏（搜后无匹配同理——「找得到、碍不着」）
                if (blockedFiltered.isNotEmpty()) {
                    item { GroupHeader(stringResource(R.string.client_blocked_section), Modifier.padding(top = 8.dp)) }
                    items(blockedFiltered, key = { "blocked_${it.mac}" }) { entry ->
                        BlockedRow(entry, busy = state.busyMac == entry.mac) { mac, name ->
                            requestWrite("unblock", mac, name, null)
                        }
                    }
                }
                if (staticFiltered.isNotEmpty()) {
                    item { GroupHeader(stringResource(R.string.client_static_section), Modifier.padding(top = 8.dp)) }
                    items(staticFiltered, key = { "static_${it.section}" }) { entry ->
                        StaticRow(entry, busy = state.busyMac == entry.mac.lowercase()) { mac, name ->
                            requestWrite("unbindStatic", mac, name, null)
                        }
                    }
                }
            }
        }
        // 写操作确认弹窗（各参数取动作行点击时快照，弹窗期间轮询翻转由 VM 防重兜底）
        pendingAction?.let { action ->
            WriteConfirmDialog(
                action = action,
                name = pendingName.orEmpty(),
                ip = pendingIp,
                onConfirm = {
                    val mac = pendingMac
                    if (mac != null) {
                        when (action) {
                            "block" -> vm.toggleBlock(mac, true)
                            "unblock" -> vm.toggleBlock(mac, false)
                            "bindStatic" -> pendingIp?.let { vm.bindStatic(mac, it, pendingName) }
                            "unbindStatic" -> vm.unbindStatic(mac)
                        }
                    }
                    pendingAction = null
                },
                onDismiss = { pendingAction = null },
            )
        }
    }
}

/** 状态汇总卡：无线客户端（assocList 精确）/ DHCP 租约（双栈合计）——只给确定计数。
 *  两半居中、数值 28sp 同首页大值档；未拉到/首拉失败显「--」（0 是确定值，不能冒充未拉到），
 *  已加载后的静默刷新失败保留旧计数。 */
@Composable
private fun SummaryCard(state: ClientUiState) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val ok = badgeToneColor(BadgeTone.OK)
            val wirelessValue = when {
                state.loading -> "--"
                state.loadFailed && state.wirelessClients.isEmpty() -> "--"
                else -> state.wirelessClients.size.toString()
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                KpiValue(
                    label = stringResource(R.string.client_wireless_clients),
                    value = wirelessValue,
                    valueColor = ok,
                    labelColor = ok,
                    valueSize = 28.sp,
                    alignment = Alignment.CenterHorizontally,
                )
            }
            Box(
                Modifier
                    .width(1.dp)
                    .height(32.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            val leaseCount = state.dhcpv4.size + state.dhcpv6.size
            val leaseValue = when {
                !state.leasesLoaded || state.leasesFailed -> "--"
                else -> leaseCount.toString()
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                KpiValue(
                    label = stringResource(R.string.client_dhcp_leases),
                    value = leaseValue,
                    valueSize = 28.sp,
                    alignment = Alignment.CenterHorizontally,
                )
            }
        }
    }
}

/** 无线客户端行：名称（+已拉黑徽章）+ IP + 信号条；展开区 = MAC/连接时长/频段 + 拉黑/静态动作 */
@Composable
private fun WirelessRow(client: WifiClient, actions: ClientActions) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            client.hostname ?: client.mac,
                            Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (actions.blocked) {
                            Spacer(Modifier.width(6.dp))
                            StatusBadge(stringResource(R.string.client_blocked), BadgeTone.ERR)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        client.ip?.let {
                            Text(
                                it,
                                Modifier.padding(end = 8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        SignalBars(client.signal)
                    }
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 8.dp).size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp)) {
                    CopyableRow(label = stringResource(R.string.client_mac), value = client.mac)
                    InfoRow(stringResource(R.string.client_connection_time), Format.duration(client.connectedTime))
                    client.band?.let { InfoRow(stringResource(R.string.client_band), it) }
                    actions.onToggleBlock?.let { onToggle ->
                        ActionRow(
                            icon = if (actions.blocked) Icons.Filled.Check else Icons.Filled.Block,
                            text = stringResource(
                                if (actions.blocked) R.string.client_unblock_action else R.string.client_block_action,
                            ),
                            tint = badgeToneColor(if (actions.blocked) BadgeTone.OK else BadgeTone.ERR),
                            busy = actions.busy,
                            onClick = onToggle,
                        )
                    }
                    actions.onBindStatic?.let { onBind ->
                        ActionRow(
                            icon = Icons.Filled.PushPin,
                            text = stringResource(R.string.client_static_action, client.ip.orEmpty()),
                            tint = MaterialTheme.colorScheme.primary,
                            busy = actions.busy,
                            onClick = onBind,
                        )
                    }
                }
            }
        }
    }
}

/** 租约行：名称（+已拉黑徽章）+ IP + 静态徽章/「动态 · 剩余 X」；展开区 = MAC（v4）/ MAC + DUID（v6）+ 动作。
 *  v6 DUID-only 租约（无 macaddr）不显动作行。 */
@Composable
private fun LeaseRow(lease: DhcpLease, isStatic: Boolean, isV6: Boolean, actions: ClientActions) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            lease.hostname
                                ?: lease.macaddr
                                ?: stringResource(R.string.client_no_hostname),
                            Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (actions.blocked) {
                            Spacer(Modifier.width(6.dp))
                            StatusBadge(stringResource(R.string.client_blocked), BadgeTone.ERR)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            lease.ip,
                            Modifier.padding(end = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (isStatic) {
                            StatusBadge(stringResource(R.string.client_static), BadgeTone.NEUTRAL, dot = false)
                        } else {
                            Text(
                                stringResource(
                                    R.string.client_lease_remaining,
                                    Format.duration(lease.expires),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 8.dp).size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp)) {
                    // v6 租约可能无 macaddr（DUID-only 客户端），有才显行
                    lease.macaddr?.let { CopyableRow(stringResource(R.string.client_mac), it) }
                    if (isV6) lease.duid?.let { CopyableRow(stringResource(R.string.client_duid), it) }
                    actions.onToggleBlock?.let { onToggle ->
                        ActionRow(
                            icon = if (actions.blocked) Icons.Filled.Check else Icons.Filled.Block,
                            text = stringResource(
                                if (actions.blocked) R.string.client_unblock_action else R.string.client_block_action,
                            ),
                            tint = badgeToneColor(if (actions.blocked) BadgeTone.OK else BadgeTone.ERR),
                            busy = actions.busy,
                            onClick = onToggle,
                        )
                    }
                    actions.onBindStatic?.let { onBind ->
                        ActionRow(
                            icon = Icons.Filled.PushPin,
                            text = stringResource(R.string.client_static_action, lease.ip),
                            tint = MaterialTheme.colorScheme.primary,
                            busy = actions.busy,
                            onClick = onBind,
                        )
                    }
                    actions.onUnbindStatic?.let { onUnbind ->
                        ActionRow(
                            icon = Icons.Filled.PushPin,
                            text = stringResource(R.string.client_unstatic_action),
                            tint = badgeToneColor(BadgeTone.WARN),
                            busy = actions.busy,
                            onClick = onUnbind,
                        )
                    }
                }
            }
        }
    }
}

/** 已封禁区块条目：MAC 主键 + 尽力反查的名称/IP（动态租约 → 静态租约；查不到为 null 只显 MAC） */
private data class BlockedEntry(val mac: String, val name: String?, val ip: String?)

/** 已封禁行：禁止图标 + 名称/MAC；展开区 MAC（复制）+ 解除动作——
 *  与客户端行同一确认弹窗状态机，设备离线/无租约也可解除 */
@Composable
private fun BlockedRow(entry: BlockedEntry, busy: Boolean, onUnblock: (mac: String, name: String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(32.dp)
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Block,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(
                        entry.name ?: entry.mac,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (entry.name != null || entry.ip != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (entry.name != null) {
                                Text(
                                    entry.mac,
                                    Modifier.padding(end = 8.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            entry.ip?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 8.dp).size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp)) {
                    CopyableRow(label = stringResource(R.string.client_mac), value = entry.mac)
                    ActionRow(
                        icon = Icons.Filled.Check,
                        text = stringResource(R.string.client_unblock_action),
                        tint = badgeToneColor(BadgeTone.OK),
                        busy = busy,
                        onClick = { onUnblock(entry.mac, entry.name ?: entry.mac) },
                    )
                }
            }
        }
    }
}

/** 静态租约行：图钉 + 名称/MAC + 绑定 IP；展开区 MAC（复制）+ 取消绑定——
 *  离线绑定设备同样可管理（与已封禁组同款解耦） */
@Composable
private fun StaticRow(entry: StaticHost, busy: Boolean, onUnbind: (mac: String, name: String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(32.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            RoundedCornerShape(10.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(
                        entry.name ?: entry.mac,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (entry.name != null) {
                            Text(
                                entry.mac,
                                Modifier.padding(end = 8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        entry.ip?.let {
                            Text(
                                stringResource(R.string.client_static_bind_ip, it),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 8.dp).size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp)) {
                    CopyableRow(label = stringResource(R.string.client_mac), value = entry.mac)
                    ActionRow(
                        icon = Icons.Filled.PushPin,
                        text = stringResource(R.string.client_unstatic_action),
                        tint = badgeToneColor(BadgeTone.WARN),
                        busy = busy,
                        onClick = { onUnbind(entry.mac, entry.name ?: entry.mac) },
                    )
                }
            }
        }
    }
}

/** 写操作动作行：icon/text/tint 参数化，四动作共用；
 *  busy 转圈禁点（防重复提交） */
@Composable
private fun ActionRow(icon: ImageVector, text: String, tint: Color, busy: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(tint.copy(alpha = 0.12f))
            .clickable(enabled = !busy, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = tint)
        } else {
            Icon(icon, contentDescription = null, Modifier.size(14.dp), tint = tint)
        }
        Text(
            text,
            Modifier.padding(start = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            color = tint,
        )
    }
}

/** 写操作确认弹窗：标题/正文/确认键按动作切换；设为静态为添加性操作用
 *  primary 确认键，其余（移除/限制类）用 danger 色；静态正文写明生效语义（设备下次续租/重连才切 IP） */
@Composable
private fun WriteConfirmDialog(
    action: String,
    name: String,
    ip: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val (titleRes, bodyRes, okRes) = when (action) {
        "unblock" -> Triple(
            R.string.client_unblock_confirm_title,
            R.string.client_unblock_confirm_body,
            R.string.client_unblock_confirm_ok,
        )
        "bindStatic" -> Triple(
            R.string.client_static_confirm_title,
            R.string.client_static_confirm_body,
            R.string.client_static_confirm_ok,
        )
        "unbindStatic" -> Triple(
            R.string.client_unstatic_confirm_title,
            R.string.client_unstatic_confirm_body,
            R.string.client_unstatic_confirm_ok,
        )
        else -> Triple(
            R.string.client_block_confirm_title,
            R.string.client_block_confirm_body,
            R.string.client_block_confirm_ok,
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(titleRes, name))
        },
        text = {
            Text(
                if (action == "bindStatic") {
                    stringResource(bodyRes, ip.orEmpty())
                } else {
                    stringResource(bodyRes)
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(okRes),
                    color = if (action == "bindStatic") {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/** 行级写操作动作包：null 回调 = 该动作对此行不可见（如无 IP 不能绑定、已绑定不显绑定动作） */
private data class ClientActions(
    val blocked: Boolean = false,
    val staticBound: Boolean = false,
    val busy: Boolean = false,
    val onToggleBlock: (() -> Unit)? = null,
    val onBindStatic: (() -> Unit)? = null,
    val onUnbindStatic: (() -> Unit)? = null,
)

/** 无线客户端区块四态：加载中 / 首拉失败 / 空（区分搜索无匹配）/ 数据。
 *  onRequestWrite 带动作行点击时的参数快照（弹窗期间轮询翻转不串语义）。 */
private fun LazyListScope.wirelessSection(
    wireless: List<WifiClient>,
    state: ClientUiState,
    staticMacSet: Set<String>,
    searchBlank: Boolean,
    onRequestWrite: (action: String, mac: String, name: String?, ip: String?) -> Unit,
) {
    when {
        state.loading && state.wirelessClients.isEmpty() ->
            item { SectionEmpty(stringResource(R.string.client_wireless_clients_loading)) }
        wireless.isEmpty() && state.loadFailed ->
            item { SectionEmpty(stringResource(R.string.common_load_failed)) }
        wireless.isEmpty() -> item {
            SectionEmpty(
                stringResource(
                    if (!searchBlank) R.string.client_no_match else R.string.client_no_wireless_clients,
                ),
            )
        }
        else -> items(wireless, key = { it.mac }) { client ->
            val macNorm = client.mac.lowercase()
            val macUpper = client.mac.uppercase()
            WirelessRow(
                client,
                ClientActions(
                    blocked = macNorm in state.blockedMacs,
                    staticBound = macUpper in staticMacSet,
                    busy = state.busyMac == macNorm,
                    onToggleBlock = {
                        val action = if (macNorm in state.blockedMacs) "unblock" else "block"
                        onRequestWrite(action, client.mac, client.hostname ?: client.mac, null)
                    },
                    onBindStatic = client.ip?.takeIf { macUpper !in staticMacSet }?.let { ip ->
                        { onRequestWrite("bindStatic", client.mac, client.hostname, ip) }
                    },
                ),
            )
        }
    }
}

/** DHCP 租约区块四态：首拉未落地加载态 / 首拉失败无旧值失败态 / 空（区分搜索无匹配）/ 数据（v4+v6 合列） */
private fun LazyListScope.leaseSection(
    leases4: List<DhcpLease>,
    leases6: List<DhcpLease>,
    state: ClientUiState,
    staticMacSet: Set<String>,
    searchBlank: Boolean,
    onRequestWrite: (action: String, mac: String, name: String?, ip: String?) -> Unit,
) {
    when {
        // 首拉未落地 → 加载态（拉到与否都算「拉过」，之后不再闪加载/失败文案）
        !state.leasesLoaded && !state.leasesFailed ->
            item { SectionEmpty(stringResource(R.string.client_leases_loading)) }
        // 首拉失败且无旧值 → 失败态（区别于「暂无租约」；轮询 3s 自动重试恢复）
        state.leasesFailed && leases4.isEmpty() && leases6.isEmpty() ->
            item { SectionEmpty(stringResource(R.string.common_load_failed)) }
        leases4.isEmpty() && leases6.isEmpty() -> item {
            SectionEmpty(
                stringResource(
                    if (!searchBlank) R.string.client_no_match else R.string.client_no_leases,
                ),
            )
        }
        else -> {
            items(leases4, key = { "v4_${it.macaddr}_${it.ip}" }) { lease ->
                blockableLease(lease, state, staticMacSet, isV6 = false, onRequestWrite)
            }
            items(leases6, key = { "v6_${it.ip}_${it.duid}" }) { lease ->
                blockableLease(lease, state, staticMacSet, isV6 = true, onRequestWrite)
            }
        }
    }
}

@Composable
private fun blockableLease(
    lease: DhcpLease,
    state: ClientUiState,
    staticMacSet: Set<String>,
    isV6: Boolean,
    onRequestWrite: (action: String, mac: String, name: String?, ip: String?) -> Unit,
) {
    val macNorm = lease.macaddr?.lowercase()
    val macUpper = lease.macaddr?.uppercase()
    val staticBound = macUpper != null && macUpper in staticMacSet
    LeaseRow(
        lease,
        isStatic = staticBound,
        isV6 = isV6,
        ClientActions(
            blocked = macNorm != null && macNorm in state.blockedMacs,
            staticBound = staticBound,
            busy = macNorm != null && state.busyMac == macNorm,
            onToggleBlock = macNorm?.let { norm ->
                {
                    val action = if (norm in state.blockedMacs) "unblock" else "block"
                    onRequestWrite(action, lease.macaddr ?: norm, lease.hostname ?: lease.macaddr ?: norm, null)
                }
            },
            // 无 MAC（v6 DUID-only）不显任何写动作：绑定静态租约以 MAC 为主键，
            // 空 MAC 会写出无效的 uci @host
            onBindStatic = if (macUpper != null && !staticBound) {
                {
                    onRequestWrite("bindStatic", lease.macaddr ?: "", lease.hostname, lease.ip)
                }
            } else {
                null
            },
            onUnbindStatic = if (staticBound) {
                { onRequestWrite("unbindStatic", lease.macaddr ?: "", lease.hostname, null) }
            } else {
                null
            },
        ),
    )
}

@Composable
private fun SearchField(search: String, onSearch: (String) -> Unit) {
    OutlinedTextField(
        value = search,
        onValueChange = onSearch,
        placeholder = { Text(stringResource(R.string.client_search_placeholder)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        trailingIcon = {
            if (search.isNotEmpty()) {
                IconButton(onClick = { onSearch("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.client_search_clear))
                }
            }
        },
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
    )
}

@Composable
private fun SectionEmpty(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(vertical = 16.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/** 按关键字过滤（大小写不敏感，空白=不过滤）；fields 给出参与匹配的候选串 */
private fun <T> List<T>.matching(search: String, fields: (T) -> List<String?>): List<T> {
    val kw = search.trim().lowercase()
    if (kw.isEmpty()) return this
    return filter { item -> fields(item).filterNotNull().any { it.lowercase().contains(kw) } }
}
