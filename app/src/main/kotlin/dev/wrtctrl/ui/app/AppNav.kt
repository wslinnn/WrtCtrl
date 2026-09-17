package dev.wrtctrl.ui.app

import android.app.Application
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.wrtctrl.R
import dev.wrtctrl.bridge.WrtCore
import dev.wrtctrl.ui.screen.DeviceGateScreen
import dev.wrtctrl.viewmodel.AppViewModel
import dev.wrtctrl.viewmodel.Phase

private data class TabSpec(@StringRes val labelRes: Int, val icon: ImageVector)

private val TABS = listOf(
    TabSpec(R.string.tabbar_home, Icons.Filled.Home),
    TabSpec(R.string.tabbar_statistics, Icons.Filled.Insights),
    TabSpec(R.string.tabbar_client, Icons.Filled.Devices),
    TabSpec(R.string.tabbar_network, Icons.Filled.Lan),
    TabSpec(R.string.tabbar_apps, Icons.Filled.Apps),
)

/** 应用入口：Boot（探活上次设备）→ Gate（设备门控页）→ Main（五 Tab） */
@Composable
fun AppRoot() {
    val app = LocalContext.current.applicationContext as Application
    val vm: AppViewModel = viewModel(factory = viewModelFactory { initializer { AppViewModel(app) } })
    val phase by vm.phase.collectAsStateWithLifecycle()

    when (phase) {
        Phase.Boot -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        Phase.Gate -> DeviceGateScreen(vm)
        Phase.Main -> MainTabs(vm)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTabs(vm: AppViewModel) {
    var selected by remember { mutableIntStateOf(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(TABS[selected].labelRes)) },
                actions = {
                    // 设备切换入口（与门控页列表共用数据层，规格 B9）
                    IconButton(onClick = { vm.openDeviceList() }) {
                        Icon(Icons.Filled.Devices, contentDescription = stringResource(R.string.device_list_history_title))
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                TABS.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (selected) {
                0 -> HomePlaceholder(vm, Modifier.fillMaxSize())
                else -> PlaceholderText(stringResource(TABS[selected].labelRes), Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun HomePlaceholder(vm: AppViewModel, modifier: Modifier = Modifier) {
    // 初始保留：JNI 链路 + panic 防线；将替换为仪表盘
    val device by vm.current.collectAsStateWithLifecycle()
    val coreHello = remember { runCatching { WrtCore.hello() }.getOrElse { "JNI ERROR: ${it.message}" } }
    var panicResult by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        device?.let {
            Text(
                stringResource(R.string.device_list_switch_current) + ": " + it.displayName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(coreHello, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = { panicResult = runCatching { WrtCore.panicTest() }.getOrElse { "FAILED: ${it.message}" } }) {
            Text(stringResource(R.string.debug_panic_drill))
        }
        panicResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun PlaceholderText(text: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.headlineSmall)
    }
}
