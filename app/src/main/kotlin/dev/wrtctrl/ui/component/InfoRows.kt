package dev.wrtctrl.ui.component

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
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

/** 标签 + 值信息行（网络页起提炼；/复用） */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            Modifier.width(104.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 可复制信息行：点击整行复制 value 并以 toast 反馈；display 缺省与 value 一致
 *  （IPv6 等长值场景：显示截断、复制完整） */
@Composable
fun CopyableRow(label: String, value: String, modifier: Modifier = Modifier, display: String = value) {
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
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            Modifier.width(104.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(display, style = MaterialTheme.typography.bodyMedium)
    }
}
