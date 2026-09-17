package dev.wrtctrl.ui.app

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.wrtctrl.R
import dev.wrtctrl.bridge.WrtCore

private data class TabSpec(@StringRes val labelRes: Int, val icon: ImageVector)

private val TABS = listOf(
    TabSpec(R.string.tabbar_home, Icons.Filled.Home),
    TabSpec(R.string.tabbar_statistics, Icons.Filled.Insights),
    TabSpec(R.string.tabbar_client, Icons.Filled.Devices),
    TabSpec(R.string.tabbar_network, Icons.Filled.Lan),
    TabSpec(R.string.tabbar_apps, Icons.Filled.Apps),
)

/** M0 骨架：五 Tab 导航壳；各 Tab 内容页在 M2/M3 逐个落位 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    var selected by remember { mutableIntStateOf(0) }
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(TABS[selected].labelRes)) }) },
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
                0 -> HomePlaceholder(Modifier.fillMaxSize())
                else -> PlaceholderText(stringResource(TABS[selected].labelRes), Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun HomePlaceholder(modifier: Modifier = Modifier) {
    // 初始：显示 Rust core 的 hello 返回值，验证 Kotlin → JNI → Rust 链路
    val coreHello = remember { runCatching { WrtCore.hello() }.getOrElse { "JNI ERROR: ${it.message}" } }
    var panicResult by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
