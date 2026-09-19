package dev.wrtctrl.ui.app

import android.app.Application
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import java.io.File
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.appcompat.app.AppCompatDelegate
import dev.wrtctrl.R
import dev.wrtctrl.data.ThemeMode
import dev.wrtctrl.data.ThemePrefs
import dev.wrtctrl.ui.screen.DashboardEditScreen
import dev.wrtctrl.ui.screen.DeviceGateScreen
import dev.wrtctrl.ui.screen.HomeScreen
import dev.wrtctrl.ui.screen.LanguageAction
import dev.wrtctrl.ui.screen.LanguageScreen
import dev.wrtctrl.ui.screen.NetworkScreen
import dev.wrtctrl.ui.screen.ThemeAction
import dev.wrtctrl.viewmodel.AppViewModel
import dev.wrtctrl.viewmodel.HomeViewModel
import dev.wrtctrl.viewmodel.NetworkViewModel
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
    var showLanguage by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var crashText by remember { mutableStateOf<String?>(null) }
    // 深浅色三态全局生效：ThemeAction 只写偏好，这里集中驱动 AppCompatDelegate
    val themePrefs = remember { ThemePrefs(app.applicationContext) }
    val themeMode by themePrefs.modeFlow().collectAsStateWithLifecycle(ThemeMode.FOLLOW_SYSTEM)
    LaunchedEffect(themeMode) {
        AppCompatDelegate.setDefaultNightMode(
            when (themeMode) {
                ThemeMode.FOLLOW_SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            },
        )
    }
    LaunchedEffect(Unit) {
        val file = File(context.filesDir, "last_crash.txt")
        if (file.exists()) crashText = runCatching { file.readText() }.getOrNull()
    }

    // 崩溃卡用浮层呈现：状态栏避让 + 悬浮于内容上方（不挤压布局、不产生空隙）
    Box(Modifier.fillMaxSize()) {
        when {
            showLanguage -> LanguageScreen(onBack = { showLanguage = false })
            phase == Phase.Boot -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            phase == Phase.Gate -> DeviceGateScreen(vm, onOpenLanguage = { showLanguage = true })
            phase == Phase.Main -> MainTabs(vm, onOpenLanguage = { showLanguage = true })
        }
        crashText?.let { crash ->
            Card(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(8.dp)
                    .align(Alignment.TopCenter),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                        Text(
                            stringResource(R.string.debug_last_crash),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        TextButton(onClick = {
                            context.filesDir.resolve("last_crash.txt").delete()
                            crashText = null
                        }) {
                            Text(
                                stringResource(R.string.common_delete),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                    Text(
                        crash,
                        Modifier.height(160.dp).verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTabs(vm: AppViewModel, onOpenLanguage: () -> Unit) {
    var selected by remember { mutableIntStateOf(0) }
    var showDashboardEdit by remember { mutableStateOf(false) }
    val app = LocalContext.current.applicationContext as Application
    val homeVm: HomeViewModel = viewModel(
        factory = viewModelFactory { initializer { HomeViewModel(app) } }
    )
    val networkVm: NetworkViewModel = viewModel(
        factory = viewModelFactory { initializer { NetworkViewModel(app) } }
    )
    // 网络页按当前设备失效缓存（切设备重拉）
    val currentDevice by vm.current.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(TABS[selected].labelRes)) },
                actions = {
                    // 仪表盘卡片编辑入口（仅首页 Tab；全局入口惯例=顶栏右上角）
                    if (selected == 0) {
                        IconButton(onClick = { showDashboardEdit = true }) {
                            Icon(Icons.Filled.Tune, contentDescription = stringResource(R.string.dashboard_edit_title))
                        }
                    }
                    // 设备切换入口（与门控页列表共用数据层，规格 B9）
                    IconButton(onClick = { vm.openDeviceList(fromMain = true) }) {
                        Icon(Icons.Filled.Devices, contentDescription = stringResource(R.string.device_list_history_title))
                    }
                    ThemeAction()
                    LanguageAction(onOpenLanguage)
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
                0 -> {
                    // 注意：Scaffold padding 已由外层 Box 消费，此处不可再叠加（双重空白的根因）
                    if (showDashboardEdit) {
                        DashboardEditScreen(homeVm, onBack = { showDashboardEdit = false })
                    } else {
                        HomeScreen(homeVm, Modifier.fillMaxSize())
                    }
                }
                3 -> NetworkScreen(networkVm, currentDevice?.id, Modifier.fillMaxSize())
                else -> PlaceholderText(stringResource(TABS[selected].labelRes), Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun PlaceholderText(text: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.headlineSmall)
    }
}
