package dev.wrtctrl.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/**
 * KPI 三层结构（label → value → context）与入口词链接：
 * 首页先行，后续统计/客户端/网络与插件页复用。字阶固定在组件内，调用方不拼裸 Text。
 * 数值单位独立：unit 由 Format.rateParts 逐值提供，组件不补省略逻辑。
 * valueSize 缺省 Unspecified 时沿用 titleLarge 字阶。
 */

/** 等宽数字：速率/百分比高频变化时不因数字宽度抖动带动布局跳动 */
private const val FONT_FEATURE_TABULAR = "tnum"

@Composable
fun KpiValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    context: String? = null,
    valueColor: Color = Color.Unspecified,
    labelColor: Color = Color.Unspecified,
    valueSize: TextUnit = TextUnit.Unspecified,
    alignment: Alignment.Horizontal = Alignment.Start,
) {
    Column(modifier, horizontalAlignment = alignment) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = labelColor)
        Row {
            Text(
                value,
                Modifier.alignByBaseline(),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = valueSize,
                    fontFeatureSettings = FONT_FEATURE_TABULAR,
                ),
                fontWeight = FontWeight.Bold,
                color = valueColor,
            )
            if (!unit.isNullOrBlank()) {
                Spacer(Modifier.width(3.dp))
                Text(
                    unit,
                    Modifier.alignByBaseline(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!context.isNullOrBlank()) {
            Text(
                context,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** KPI 横排容器：间距用固定设计值，内容左聚（不排满不留悬空权重） */
@Composable
fun KpiGrid(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(32.dp),
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = Alignment.Top,
        content = content,
    )
}

/** 入口词链接：词表封闭（趋势 →/详情 →/诊断 →/放大），箭头只跟页面跳转。
 *  onClick 置尾以支持尾随 lambda 调用。 */
@Composable
fun EntryLink(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Medium,
    )
}
