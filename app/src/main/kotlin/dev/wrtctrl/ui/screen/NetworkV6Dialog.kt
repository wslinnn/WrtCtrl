package dev.wrtctrl.ui.screen

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.wrtctrl.R
import dev.wrtctrl.ui.component.CopyableRow
import dev.wrtctrl.ui.component.QrCodeImage
import dev.wrtctrl.util.WifiQr
import dev.wrtctrl.viewmodel.WifiSecret

/** 网络页弹层与共享小件：IPv6/PD 详情、WiFi 认证与二维码弹窗、复制图标按钮 */

/** IPv6 / PD 详情弹窗：分节标题 + 每条可复制（长列表内滚动） */
@Composable
internal fun V6DetailDialog(title: String, sections: List<Pair<String, List<String>>>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                sections.forEach { (section, items) ->
                    Text(
                        section,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    items.forEach { CopyableRow(section, it) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}

@Composable
internal fun EyeButton(contentDescription: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        Icon(
            Icons.Filled.Visibility,
            contentDescription = contentDescription,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** IPv6/PD 行显示值截断：超 30 字符截断加省略号（完整值进弹窗或点击复制） */
internal fun truncate30(s: String): String = if (s.length > 30) s.take(30) + "…" else s

/** 复制图标按钮：值旁固定 copy 标识，点击复制 + toast（SSID 卡头等非 CopyableRow 场景） */
@Composable
internal fun CopyIconButton(text: String, contentDescription: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    IconButton(
        onClick = {
            clipboard.setText(AnnotatedString(text))
            Toast.makeText(context, context.getString(R.string.common_copied), Toast.LENGTH_SHORT).show()
        },
        modifier = Modifier.size(28.dp),
    ) {
        Icon(
            Icons.Filled.ContentCopy,
            contentDescription = contentDescription,
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 设备认证弹窗已移除（本地密码认证无意义）——WiFi 动作直接拉凭据显码 */

/** WiFi 二维码弹窗：大码扫码入网 + SSID/密码行（显隐 + 复制）。
 *  secret 未落地转圈；拉取失败显失败态 + 原因（一键复制诊断）+ 重试；
 *  企业网（802.1x）提示不适用；开放网显「未设密码」免密码行 */
@Composable
internal fun WifiQrDialog(
    ifname: String,
    secret: WifiSecret?,
    errorText: String?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(secret?.ssid ?: ifname) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    secret == null && errorText != null -> {
                        Text(
                            stringResource(R.string.wifi_secret_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // 诊断信息原样展示 + 一键复制（无 adb 远程验收的排障通道）
                        Text(
                            errorText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row {
                            TextButton(onClick = {
                                clipboard.setText(AnnotatedString("wifi: $ifname\n$errorText"))
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.common_copied),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }) {
                                Text(stringResource(R.string.common_copy))
                            }
                            TextButton(onClick = onRetry) { Text(stringResource(R.string.wifi_secret_retry)) }
                        }
                    }
                    secret == null -> CircularProgressIndicator(Modifier.size(48.dp))

                    !WifiQr.isPersonal(secret.encryption) -> Text(
                        stringResource(R.string.wifi_not_shareable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> {
                        val content = remember(secret) {
                            WifiQr.payload(secret.encryption, secret.ssid ?: ifname, secret.key)
                        }
                        QrCodeImage(
                            content,
                            Modifier.fillMaxWidth(0.66f).padding(vertical = 8.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        if (secret.key == null) {
                            Text(
                                stringResource(R.string.wifi_open_network),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            var showKey by remember(secret) { mutableStateOf(false) }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        stringResource(R.string.wifi_ssid_label),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(secret.ssid ?: ifname, style = MaterialTheme.typography.bodyMedium)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        stringResource(R.string.device_list_password),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            if (showKey) secret.key else "••••••",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        IconButton(
                                            onClick = { showKey = !showKey },
                                            modifier = Modifier.size(28.dp),
                                        ) {
                                            Icon(
                                                if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                                contentDescription = stringResource(
                                                    if (showKey) {
                                                        R.string.device_list_password_hide
                                                    } else {
                                                        R.string.device_list_password_show
                                                    },
                                                ),
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        CopyIconButton(secret.key, stringResource(R.string.common_copy))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}
