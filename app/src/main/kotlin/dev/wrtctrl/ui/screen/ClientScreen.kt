package dev.wrtctrl.ui.screen

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    // 可见才轮询：组合级可见（底部 Tab 选中）× 生命周期双门控（共享 PollingGate）
    PollingGate(onActiveChange = vm::setPollingActive)

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
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { SummaryCard(state) }
                item { GroupHeader(stringResource(R.string.client_wireless_clients), Modifier.padding(top = 4.dp)) }
                when {
                    state.loading && state.wirelessClients.isEmpty() ->
                        item { SectionEmpty(stringResource(R.string.client_wireless_clients_loading)) }
                    wireless.isEmpty() && state.loadFailed ->
                        item { SectionEmpty(stringResource(R.string.common_load_failed)) }
                    wireless.isEmpty() -> item {
                        SectionEmpty(
                            stringResource(
                                if (search.isNotBlank()) R.string.client_no_match else R.string.client_no_wireless_clients,
                            ),
                        )
                    }
                    else -> items(wireless, key = { it.mac }) { WirelessRow(it) }
                }
                item { GroupHeader(stringResource(R.string.client_dhcp_leases), Modifier.padding(top = 8.dp)) }
                when {
                    // 首拉未落地 → 加载态（拉到与否都算「拉过」，之后不再闪加载/失败文案）
                    !state.leasesLoaded && !state.leasesFailed ->
                        item { SectionEmpty(stringResource(R.string.client_leases_loading)) }
                    // 首拉失败且无旧值 → 失败态（区别于「暂无租约」；轮询 3s 自动重试恢复）
                    state.leasesFailed && leases4.isEmpty() && leases6.isEmpty() ->
                        item { SectionEmpty(stringResource(R.string.common_load_failed)) }
                    leases4.isEmpty() && leases6.isEmpty() -> {
                        item {
                            SectionEmpty(
                                stringResource(
                                    if (search.isNotBlank()) R.string.client_no_match else R.string.client_no_leases,
                                ),
                            )
                        }
                    }
                    else -> {
                        items(leases4, key = { "v4_${it.macaddr}_${it.ip}" }) {
                            LeaseRow(it, isStatic = it.macaddr?.uppercase() in state.staticMacs, isV6 = false)
                        }
                        items(leases6, key = { "v6_${it.ip}_${it.duid}" }) {
                            LeaseRow(it, isStatic = it.macaddr?.uppercase() in state.staticMacs, isV6 = true)
                        }
                    }
                }
            }
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

/** 无线客户端行：名称 + IP + 信号条；展开区 = MAC（复制）/ 连接时长 / 频段（行减法） */
@Composable
private fun WirelessRow(client: WifiClient) {
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
                    Text(
                        client.hostname ?: client.mac,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
                }
            }
        }
    }
}

/** 租约行：名称 + IP + 静态徽章/「动态 · 剩余 X」；展开区 = MAC（v4）/ MAC + DUID（v6） */
@Composable
private fun LeaseRow(lease: DhcpLease, isStatic: Boolean, isV6: Boolean) {
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
                    Text(
                        lease.hostname
                            ?: lease.macaddr
                            ?: stringResource(R.string.client_no_hostname),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
                }
            }
        }
    }
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
