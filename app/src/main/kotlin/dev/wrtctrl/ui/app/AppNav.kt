package dev.wrtctrl.ui.app

import android.app.Application
import android.widget.Toast
import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.appcompat.app.AppCompatDelegate
import dev.wrtctrl.R
import dev.wrtctrl.data.Device
import dev.wrtctrl.data.ThemeMode
import dev.wrtctrl.data.ThemePrefs
import dev.wrtctrl.ui.screen.AppsScreen
import dev.wrtctrl.ui.screen.ClientScreen
import dev.wrtctrl.ui.screen.DashboardEditScreen
import dev.wrtctrl.ui.screen.DeviceGateScreen
import dev.wrtctrl.ui.screen.HomeScreen
import dev.wrtctrl.ui.screen.LanguageAction
import dev.wrtctrl.ui.screen.NetworkScreen
import dev.wrtctrl.ui.screen.StatisticsScreen
import dev.wrtctrl.ui.screen.ThemeAction
import dev.wrtctrl.ui.component.BadgeTone
import dev.wrtctrl.ui.component.StatusBadge
import dev.wrtctrl.viewmodel.AppViewModel
import dev.wrtctrl.viewmodel.AppsViewModel
import dev.wrtctrl.viewmodel.ClientViewModel
import dev.wrtctrl.viewmodel.HomeViewModel
import dev.wrtctrl.viewmodel.NetworkViewModel
import dev.wrtctrl.viewmodel.StatisticsViewModel
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
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var crashText by remember { mutableStateOf<String?>(null) }
    // 主界面组合状态经 holder 跨「门控往返」保存恢复；语言/主题切换的 Activity 重建走系统 savedInstanceState 恢复
    val mainHolder = rememberSaveableStateHolder()
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
        when (phase) {
            Phase.Boot -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            Phase.Gate -> DeviceGateScreen(vm)
            Phase.Main -> mainHolder.SaveableStateProvider("main") { MainTabs(vm) }
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
                        // 一键复制崩溃堆栈（无 adb 环境，靠用户回传排障）
                        TextButton(onClick = {
                            crashText?.let {
                                clipboard.setText(AnnotatedString(it))
                                Toast.makeText(context, context.getString(R.string.common_copied), Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Text(
                                stringResource(R.string.common_copy),
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
private fun MainTabs(vm: AppViewModel) {
    // 状态保留规范：saveable 化后语言/主题切换的 Activity 重建、门控往返
    // 都回到原 Tab 与原覆盖页；各 Tab 内容经 holder 按键保存恢复滚动位置
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var showDashboardEdit by rememberSaveable { mutableStateOf(false) }
    // 首页网络卡直达工具（NAT 会话→conntrack、DOWN 接口→诊断）：切到应用 Tab 并带开工具页
    var pendingTool by rememberSaveable { mutableStateOf<String?>(null) }
    val tabHolder = rememberSaveableStateHolder()
    // 设备切换底部弹层（▾ / 顶栏设备图标共用）：连接成功（current 变更）自动收起。
    // saveable：弹层开着时语言/主题切换重建后重开——连接进行中/失败横幅不静默丢失
    var showDeviceSheet by rememberSaveable { mutableStateOf(false) }
    var sheetTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    val gate by vm.gate.collectAsStateWithLifecycle()
    val app = LocalContext.current.applicationContext as Application
    val homeVm: HomeViewModel = viewModel(
        factory = viewModelFactory { initializer { HomeViewModel(app) } }
    )
    val networkVm: NetworkViewModel = viewModel(
        factory = viewModelFactory { initializer { NetworkViewModel(app) } }
    )
    val clientVm: ClientViewModel = viewModel(
        factory = viewModelFactory { initializer { ClientViewModel(app) } }
    )
    val statisticsVm: StatisticsViewModel = viewModel(
        factory = viewModelFactory { initializer { StatisticsViewModel(app) } }
    )
    val appsVm: AppsViewModel = viewModel(
        factory = viewModelFactory { initializer { AppsViewModel(app) } }
    )
    // 网络页按当前设备失效缓存（切设备重拉）
    val currentDevice by vm.current.collectAsStateWithLifecycle()
    // 首页切设备：重置过渡态 + 立即拉取（不带上份设备数据等下个轮询节拍）
    LaunchedEffect(currentDevice?.id) { homeVm.switchDevice(currentDevice?.id) }
    // 弹层点选的设备连上后自动收起（点当前设备 = 立即收起，不重连）
    LaunchedEffect(currentDevice?.id, sheetTargetId) {
        if (sheetTargetId != null && sheetTargetId == currentDevice?.id) {
            showDeviceSheet = false
            sheetTargetId = null
        }
    }
    if (showDeviceSheet) {
        ModalBottomSheet(onDismissRequest = { showDeviceSheet = false }) {
            DeviceSheetContent(
                devices = gate.devices,
                pings = gate.pings,
                currentId = currentDevice?.id,
                connecting = gate.connecting,
                bannerError = gate.bannerError,
                onClick = { device ->
                    sheetTargetId = device.id
                    vm.connectTo(device)
                },
                onManage = {
                    showDeviceSheet = false
                    vm.openDeviceList(fromMain = true)
                },
            )
        }
    }
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
                    // 设备切换入口（底部弹层；管理走弹层内「管理设备」回门控列表）
                    IconButton(onClick = { showDeviceSheet = true; vm.openDeviceSheet() }) {
                        Icon(Icons.Filled.Devices, contentDescription = stringResource(R.string.device_list_history_title))
                    }
                    ThemeAction()
                    LanguageAction()
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
                        // 编辑页与首页同属覆盖互斥分支：holder 按键保编辑页滚动状态（与工具页同形态）
                        tabHolder.SaveableStateProvider("dash_edit") {
                            DashboardEditScreen(homeVm, onBack = { showDashboardEdit = false })
                        }
                    } else {
                        tabHolder.SaveableStateProvider(0) {
                            HomeScreen(
                                homeVm,
                                onGotoTab = { selected = it },
                                onOpenTool = {
                                    pendingTool = it
                                    selected = 4
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
                1 -> tabHolder.SaveableStateProvider(1) {
                    StatisticsScreen(statisticsVm, currentDevice?.id, Modifier.fillMaxSize())
                }
                2 -> tabHolder.SaveableStateProvider(2) {
                    ClientScreen(clientVm, currentDevice?.id, Modifier.fillMaxSize())
                }
                3 -> tabHolder.SaveableStateProvider(3) {
                    NetworkScreen(networkVm, currentDevice?.id, Modifier.fillMaxSize())
                }
                else -> tabHolder.SaveableStateProvider(4) {
                    AppsScreen(
                        appsVm,
                        currentDevice?.id,
                        pendingToolId = pendingTool,
                        onToolConsumed = { pendingTool = null },
                        onSessionLost = { vm.openDeviceList() },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/** 设备切换弹层内容：设备行（名称 + ping 徽章 + 地址，当前设备 ✓）+ 管理设备入口 */
@Composable
private fun DeviceSheetContent(
    devices: List<Device>,
    pings: Map<String, Long?>,
    currentId: String?,
    connecting: Boolean,
    bannerError: String?,
    onClick: (Device) -> Unit,
    onManage: () -> Unit,
) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        Text(
            stringResource(R.string.device_sheet_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        devices.forEach { device ->
            val ping = pings[device.id]
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = !connecting) { onClick(device) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(device.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.width(8.dp))
                        when {
                            ping != null -> StatusBadge(
                                stringResource(R.string.home_ping_online, ping),
                                BadgeTone.OK,
                            )
                            pings.containsKey(device.id) -> StatusBadge(
                                stringResource(R.string.home_ping_offline),
                                BadgeTone.ERR,
                            )
                            // 探活未落定：不占位（数据有源）
                        }
                    }
                    Text(
                        device.displayAddress,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (device.id == currentId) {
                    Text(
                        "✓",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        bannerError?.let {
            Text(
                it,
                Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onManage)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.device_sheet_manage),
                Modifier.padding(start = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
