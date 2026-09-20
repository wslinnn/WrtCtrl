package dev.wrtctrl.viewmodel

import android.os.SystemClock
import kotlinx.coroutines.delay

/** 下拉刷新指示器最短展示时长：true→false 切换过快会让 M3 PullToRefreshBox
 *  指示器停在外面不回弹（门控页实测教训）。 */
internal const val REFRESH_MIN_SPIN_MS = 400L

/** 数据落地后调用：指示器总时长 = max(拉取耗时, 400ms) */
internal suspend fun holdRefreshSpin(startedAt: Long) {
    val remain = REFRESH_MIN_SPIN_MS - (SystemClock.elapsedRealtime() - startedAt)
    if (remain > 0) delay(remain)
}
