package dev.wrtctrl.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.wrtctrl.R
import dev.wrtctrl.viewmodel.AppRegistry

/** 工具徽章语义 → 容器配色（见 ToolBadgeKind.kt） */

/**
 * 工具页共享件：ToolPage 脚手架（返回头 + 下拉刷新 +
 * 状态盒）+ 语义徽章 + monospace 文本 + 关键字过滤。
 * 工具页是组合级覆盖页（嵌在应用 Tab 内容区），必须自带 BackHandler——
 * 否则系统返回直接退桌面。
 */

/** 工具页脚手架：返回头 + 下拉刷新（onRefresh=null 则不启用）+ 内容 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolPage(
    title: String,
    onBack: () -> Unit,
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                )
            }
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        val body: @Composable () -> Unit = {
            Box(Modifier.fillMaxSize(), content = content)
        }
        if (onRefresh != null) {
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) { body() }
        } else {
            body()
        }
    }
}

/** 居中状态：spinner 优先，其次文本 */
@Composable
internal fun ToolStateBox(text: String? = null, spinner: Boolean = false) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            spinner -> CircularProgressIndicator()
            text != null -> Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}

/** 错误 + 重试按钮（route 页语义：解析失败/加载失败可手动重试） */
@Composable
internal fun ToolErrorRetry(text: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.size(12.dp))
            Button(onClick = onRetry) { Text(stringResource(R.string.route_retry)) }
        }
    }
}

/** 语义徽章（工具页专用：不侵入网络页 Badge 的 isError 二元语义） */
@Composable
internal fun ToolBadge(text: String, kind: ToolBadgeKind = ToolBadgeKind.Neutral) {
    val (container, content) = when (kind) {
        ToolBadgeKind.Neutral ->
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        ToolBadgeKind.Positive ->
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        ToolBadgeKind.Warning ->
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        ToolBadgeKind.Error ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        ToolBadgeKind.Info ->
            MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(color = container, shape = RoundedCornerShape(50)) {
        Text(
            text,
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = content,
        )
    }
}

/** monospace 小号文本（日志/诊断输出/连接端点） */
@Composable
internal fun MonoText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    style: TextStyle = MaterialTheme.typography.bodySmall,
) {
    Text(
        text,
        modifier,
        style = style.copy(fontFamily = FontFamily.Monospace),
        color = color,
    )
}

/** 关键字过滤（大小写不敏感，空白=不过滤）；fields 给出参与匹配的候选串 */
internal fun <T> List<T>.filterMatching(search: String, fields: (T) -> List<String?>): List<T> {
    val kw = search.trim().lowercase()
    if (kw.isEmpty()) return this
    return filter { item -> fields(item).filterNotNull().any { it.lowercase().contains(kw) } }
}

/** 应用中心图标 → 工具页路由（插件 id 不在此列，走「即将推出」toast） */
@Composable
fun ToolRouter(toolId: String, deviceId: String?, onBack: () -> Unit, onSessionLost: () -> Unit) {
    when (toolId) {
        "route" -> RouteScreen(deviceId, onBack)
        "startup" -> StartupScreen(deviceId, onBack)
        "process" -> ProcessScreen(deviceId, onBack)
        "syslog" -> SyslogScreen(deviceId, onBack)
        "conntrack" -> ConntrackScreen(deviceId, onBack)
        "diag" -> DiagScreen(onBack)
        "reboot" -> RebootScreen(onBack, onSessionLost)
    }
}

/** id 是否为已实装工具页（应用中心点击分流用） */
fun isToolId(id: String): Boolean = AppRegistry.tools.any { it.id == id }

/** 统一的列表容器 padding */
internal val ToolListPadding = PaddingValues(16.dp)
internal val ToolItemSpacing = Arrangement.spacedBy(12.dp)

/** 工具页搜索框（process/syslog/conntrack 共用；右侧清除钮） */
@Composable
internal fun ToolSearchField(value: String, hint: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(hint) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.client_search_clear))
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 自动刷新开关行（轮询页共用）：Switch + 标签 */
@Composable
internal fun AutoRefreshRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.syslog_auto_refresh),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 错误文案（空列表 + 失败标志时显示；与数据页 common_load_failed 同款） */
@Composable
internal fun toolLoadFailedText(): String = stringResource(R.string.common_load_failed)

/** 卡片基础形态（工具页统一 12dp 内边距） */
@Composable
internal fun ToolCard(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), content = { content() })
    }
}
