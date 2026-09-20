package dev.wrtctrl.ui.component

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.wrtctrl.R

/** 标签列宽（默认形态：网络/客户端页长标签需要列对齐） */
private val LABEL_WIDTH = 104.dp

/** 标签 + 值信息行。compact=true 时标签按内容宽 + 6dp 间距（窄列场景：
 *  固定 104dp 标签宽会把数值列挤到换行——统计页多列统计专用形态） */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            if (compact) Modifier else Modifier.width(LABEL_WIDTH),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (compact) Spacer(Modifier.width(6.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 可复制信息行（复制可发现化收敛为隐式）：整行点击复制 + toast 反馈，无视觉标识
 *  （假按钮图标不如没有）；display 缺省与 value 一致
 *  （IPv6 等长值场景：显示截断、复制完整）；compact 语义同 InfoRow */
@Composable
fun CopyableRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    display: String = value,
    compact: Boolean = false,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Row(
        modifier
            .fillMaxWidth()
            .clickable {
                clipboard.setText(AnnotatedString(value))
                Toast.makeText(context, context.getString(R.string.common_copied), Toast.LENGTH_SHORT).show()
            }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            if (compact) Modifier else Modifier.width(LABEL_WIDTH),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (compact) Spacer(Modifier.width(6.dp))
        Text(display, style = MaterialTheme.typography.bodyMedium)
    }
}
