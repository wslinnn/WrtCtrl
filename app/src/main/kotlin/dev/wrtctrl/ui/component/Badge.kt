package dev.wrtctrl.ui.component

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 卡头右侧徽章（接口 proto / 设备 up·down 状态），isError 时用错误配色 */
@Composable
fun Badge(text: String, isError: Boolean = false) {
    Surface(
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (isError) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
