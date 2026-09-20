package dev.wrtctrl.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wrtctrl.R
import dev.wrtctrl.ui.component.CopyableRow
import dev.wrtctrl.ui.component.InfoRow
import dev.wrtctrl.ui.component.PollingGate
import dev.wrtctrl.util.Format
import dev.wrtctrl.viewmodel.ClientParsers
import dev.wrtctrl.viewmodel.ClientUiState
import dev.wrtctrl.viewmodel.ClientViewModel
import dev.wrtctrl.viewmodel.DhcpLease
import dev.wrtctrl.viewmodel.WifiClient

/** 客户端页：搜索 + 无线终端 / DHCPv4 / DHCPv6 三 Tab。可见时 3s 静默轮询当前 Tab。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientScreen(vm: ClientViewModel, deviceId: String?, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(deviceId) { vm.ensureLoaded(deviceId) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(tab) {
        if (tab != 0) vm.loadDhcp()
        vm.onTab(tab)
    }
    var search by rememberSaveable { mutableStateOf("") }
    // 可见才轮询：组合级可见（底部 Tab 选中）× 生命周期双门控（共享 PollingGate）
    PollingGate(onActiveChange = vm::setPollingActive)

    Column(modifier) {
        SearchField(search, onSearch = { search = it })
        val tabs = listOf(
            R.string.client_wireless_clients,
            R.string.client_dhcpv4_allocation,
            R.string.client_dhcpv6_allocation,
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
            onRefresh = { vm.refresh(tab) },
            modifier = Modifier.fillMaxSize(),
        ) {
            when (tab) {
                0 -> WirelessTab(state, search)
                1 -> Dhcp4Tab(state, search)
                else -> Dhcp6Tab(state, search)
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
private fun WirelessTab(state: ClientUiState, search: String) {
    val clients = state.wirelessClients.matching(search) { listOf(it.mac, it.hostname) }
    when {
        state.loading -> CenterText(stringResource(R.string.client_wireless_clients_loading))
        clients.isEmpty() && state.loadFailed -> CenterText(stringResource(R.string.common_load_failed))
        clients.isEmpty() -> CenterText(
            stringResource(
                if (search.isNotBlank()) R.string.client_no_match else R.string.client_no_wireless_clients,
            ),
        )
        else -> LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(clients.size) { i -> ClientCard(clients[i]) }
        }
    }
}

@Composable
private fun ClientCard(client: WifiClient) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            // 踢人功能已整体移除
            CopyableRow(label = stringResource(R.string.client_mac), value = client.mac)
            client.hostname?.let { InfoRow(stringResource(R.string.client_hostname), it) }
            InfoRow(stringResource(R.string.client_signal), "${client.signal}dBm")
            InfoRow(stringResource(R.string.client_connection_time), Format.duration(client.connectedTime))
            InfoRow(stringResource(R.string.client_receive_rate), ClientParsers.rateText(client.rx))
            InfoRow(stringResource(R.string.client_transmit_rate), ClientParsers.rateText(client.tx))
            val bandSuffix = client.band?.let { stringResource(R.string.client_band_info, it) }.orEmpty()
            InfoRow(stringResource(R.string.client_interface), client.ifname + bandSuffix)
        }
    }
}

@Composable
private fun Dhcp4Tab(state: ClientUiState, search: String) {
    val leases = state.dhcpv4.matching(search) { listOf(it.macaddr, it.hostname, it.ip) }
    if (leases.isEmpty()) {
        CenterText(
            stringResource(
                if (search.isNotBlank()) R.string.client_no_match else R.string.client_no_dhcpv4_allocation,
            ),
        )
    } else {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(leases.size) { i -> Dhcp4Card(leases[i]) }
        }
    }
}

@Composable
private fun Dhcp4Card(lease: DhcpLease) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            CopyableRow(stringResource(R.string.client_mac), lease.macaddr ?: "-")
            InfoRow(stringResource(R.string.client_hostname), lease.hostname ?: "-")
            CopyableRow(stringResource(R.string.client_ip_address), lease.ip)
            InfoRow(stringResource(R.string.client_lease_time), Format.duration(lease.expires))
        }
    }
}

@Composable
private fun Dhcp6Tab(state: ClientUiState, search: String) {
    val leases = state.dhcpv6.matching(search) { listOf(it.macaddr, it.hostname, it.ip) }
    if (leases.isEmpty()) {
        CenterText(
            stringResource(
                if (search.isNotBlank()) R.string.client_no_match else R.string.client_no_dhcpv6_allocation,
            ),
        )
    } else {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(leases.size) { i -> Dhcp6Card(leases[i]) }
        }
    }
}

@Composable
private fun Dhcp6Card(lease: DhcpLease) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            // v6 租约可能无 macaddr（DUID-only 客户端），有才显行
            lease.macaddr?.let { CopyableRow(stringResource(R.string.client_mac), it) }
            InfoRow(stringResource(R.string.client_hostname), lease.hostname ?: "-")
            CopyableRow(stringResource(R.string.client_ipv6_address), lease.ip)
            lease.duid?.let { CopyableRow(stringResource(R.string.client_duid), it) }
            InfoRow(stringResource(R.string.client_lease_time), Format.duration(lease.expires))
        }
    }
}

@Composable
private fun CenterText(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 按关键字过滤（大小写不敏感，空白=不过滤）；fields 给出参与匹配的候选串 */
private fun <T> List<T>.matching(search: String, fields: (T) -> List<String?>): List<T> {
    val kw = search.trim().lowercase()
    if (kw.isEmpty()) return this
    return filter { item -> fields(item).filterNotNull().any { it.lowercase().contains(kw) } }
}
