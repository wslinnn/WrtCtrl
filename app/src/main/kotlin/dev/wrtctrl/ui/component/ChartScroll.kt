package dev.wrtctrl.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.patrykandpatrick.vico.compose.cartesian.AutoScrollCondition
import com.patrykandpatrick.vico.compose.cartesian.Scroll
import com.patrykandpatrick.vico.compose.cartesian.VicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState

/**
 * 实时曲线的滚动状态：默认停在最右跟随新点；用户左滑回看历史后停止自动跟随，
 * 滑回最右自动恢复跟随。Vico 自带 OnModelGrowth 是无条件拽回最右——轮询页每
 * 3s 一个新点，用户刚滑开就被拽回，历史根本看不住。
 * 判定只用公开的 value/maxValue：滚动值回落（比上次小）= 用户在回看，翻假；
 * 停在最右（≥ max-1px）翻真；仅内容增长（值不变、max 变大）保持原判——
 * 自动滚动本身会让值回升，不能把「跟随中」误判成「回看」。
 */
@Composable
internal fun rememberLiveFollowScrollState(): VicoScrollState {
    var atEnd by remember { mutableStateOf(true) }
    var prevValue by remember { mutableFloatStateOf(0f) }
    val state = rememberVicoScrollState(
        initialScroll = Scroll.Absolute.End,
        autoScrollCondition = AutoScrollCondition { _, _ -> atEnd },
    )
    LaunchedEffect(state) {
        snapshotFlow { state.value to state.maxValue }
            .collect { (value, max) ->
                atEnd = when {
                    value >= max - 1f -> true
                    value < prevValue - 0.5f -> false
                    else -> atEnd
                }
                prevValue = value
            }
    }
    return state
}
