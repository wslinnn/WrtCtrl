package dev.wrtctrl.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 分组标题：品牌色竖条 + 标题（网络页/应用中心同款视觉，原两处私有实现收敛于此）。
 *  纵向节奏两页不同（网络 top 4dp / 应用 top 12dp+bottom 4dp），由调用方 modifier 给定 */
@Composable
fun GroupHeader(title: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(4.dp)
                .height(18.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall)
    }
}
