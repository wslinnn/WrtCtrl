package dev.wrtctrl.ui.screen

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wrtctrl.R
import dev.wrtctrl.ui.component.CopyableRow
import dev.wrtctrl.ui.component.InfoRow
import dev.wrtctrl.util.Format
import dev.wrtctrl.viewmodel.ClientParsers
import dev.wrtctrl.viewmodel.ClientUiState
import dev.wrtctrl.viewmodel.ClientViewModel
import dev.wrtctrl.viewmodel.DhcpLease
import dev.wrtctrl.viewmodel.WifiClient

/** 客户端页：搜索 + 无线终端 / DHCPv4 / DHCPv6 三 Tab。无轮询。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientScreen(vm: ClientViewModel, deviceId: String?, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(deviceId) { vm.ensureLoaded(deviceId) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(tab) {
        if (tab != 0) vm.loadDhcp()
        vm.onTab(tab)
    }
    var search by rememberSaveable { mutableStateOf("") }
    // 可见才轮询：Bottom Tab 选中时本屏才组合（组合级可见），后台再叠加生命周期门控
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> vm.setPollingActive(true)
                Lifecycle.Event.ON_PAUSE -> vm.setPollingActive(false)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.setPollingActive(false)
        }
    }
    val successText = stringResource(R.string.client_disconnect_success)
    val failedText = stringResource(R.string.client_disconnect_failed)
    val kick: (WifiClient) -> Unit = { client ->
        vm.kick(client) { ok, error ->
            // 失败把 core 错误串带上（无 adb 排障：对象不存在=驱动无 hostapd 面，权限=ACL）
            Toast.makeText(
                context,
                if (ok) successText else failedText + (error?.let { "\n$it" } ?: ""),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

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
                0 -> WirelessTab(state, search, onKick = kick)
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
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.client_search_placeholder))
                }
            }
        },
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
    )
}

@Composable
private fun WirelessTab(state: ClientUiState, search: String, onKick: (WifiClient) -> Unit) {
    val clients = state.wirelessClients.matching(search) { listOf(it.mac, it.hostname) }
    when {
        state.loading -> CenterText(stringResource(R.string.client_wireless_clients_loading))
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
            items(clients.size) { i -> ClientCard(clients[i], onKick) }
        }
    }
}

@Composable
private fun ClientCard(client: WifiClient, onKick: (WifiClient) -> Unit) {
    var confirmKick by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                CopyableRow(
                    label = stringResource(R.string.client_mac),
                    value = client.mac,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { confirmKick = true }) {
                    Icon(
                        Icons.Filled.PersonRemove,
                        contentDescription = stringResource(R.string.client_kick),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            client.hostname?.let { InfoRow(stringResource(R.string.client_hostname), it) }
            InfoRow(stringResource(R.string.client_signal), "${client.signal}dBm")
            InfoRow(stringResource(R.string.client_connection_time), Format.duration(client.connectedTime))
            InfoRow(stringResource(R.string.client_receive_rate), ClientParsers.rateText(client.rx))
            InfoRow(stringResource(R.string.client_transmit_rate), ClientParsers.rateText(client.tx))
            val bandSuffix = client.band?.let { stringResource(R.string.client_band_info, it) }.orEmpty()
            InfoRow(stringResource(R.string.client_interface), client.ifname + bandSuffix)
        }
    }
    if (confirmKick) {
        AlertDialog(
            onDismissRequest = { confirmKick = false },
            title = { Text(stringResource(R.string.client_tip)) },
            text = {
                Text(
                    stringResource(
                        R.string.client_confirm_disconnect,
                        client.mac,
                        client.hostname?.let { "($it)" }.orEmpty(),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmKick = false
                    onKick(client)
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmKick = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
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
