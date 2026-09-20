package dev.wrtctrl.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.drop
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import dev.wrtctrl.R
import dev.wrtctrl.data.DashboardCardId
import dev.wrtctrl.viewmodel.HomeViewModel

private fun cardTitleRes(id: DashboardCardId): Int = when (id) {
    DashboardCardId.BANDWIDTH -> R.string.home_realtime_throughput
    DashboardCardId.RESOURCE -> R.string.home_resource_monitor
    DashboardCardId.NETWORK -> R.string.home_network_status
}

/** 编辑仪表盘：长按把手拖拽排序，Switch 控制显隐；变更即时持久化（DataStore）。
 *  嵌在 MainTabs 的 Scaffold 内容区内渲染，故不可再自带 Scaffold/TopAppBar（会重复叠加状态栏 inset 产生大空白）。
 *  BackHandler：页面是组合级分支而非导航栈页，不拦截系统返回会直接退到桌面。 */
@Composable
fun DashboardEditScreen(vm: HomeViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val state by vm.state.collectAsStateWithLifecycle()
    // 本地编辑态：初值 snapshot 一次；DataStore 持久化值落地（cardConfigLoaded）后再同步一次
    // （秒开编辑页的竞态）。此后不再随 flow 回灌——早期实现按每次发射重建本地态，
    // 快速连续拖拽时最后一次操作会被上一轮回写覆盖
    var order by remember { mutableStateOf(state.cardOrder) }
    var enabled by remember { mutableStateOf(state.cardEnabled) }
    LaunchedEffect(state.cardConfigLoaded) {
        if (state.cardConfigLoaded) {
            order = state.cardOrder
            enabled = state.cardEnabled
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { order to enabled }
            .drop(1)
            .collect { (o, e) -> vm.saveCardConfig(o, e) }
    }

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        order = order.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            }
            Text(
                stringResource(R.string.dashboard_edit_title),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Text(
            stringResource(R.string.dashboard_edit_hint),
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(order, key = { it.name }) { cardId ->
                ReorderableItem(reorderState, key = cardId.name) { isDragging ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                if (isDragging) {
                                    MaterialTheme.colorScheme.surfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                            )
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.DragHandle,
                            contentDescription = null,
                            modifier = Modifier.longPressDraggableHandle(),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(cardTitleRes(cardId)),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (cardId in enabled) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Switch(
                            checked = cardId in enabled,
                            onCheckedChange = { on ->
                                enabled = if (on) enabled + cardId else enabled - cardId
                            },
                        )
                    }
                }
            }
        }
    }
}
