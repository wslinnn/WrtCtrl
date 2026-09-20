package dev.wrtctrl.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * 数据页轮询门控：组合级可见性 × 生命周期双门。
 * 组合即活跃（addObserver 会同步回放当前 RESUMED 态，无需额外触发首轮）；
 * 离开组合（切底部 Tab / 进覆盖页）或退后台立即暂停。
 * 收敛为单一实现——此前四屏各手抄一份，首页曾因 onDispose 漏写关停导致
 * 切走后仍持续轮询。
 */
@Composable
fun PollingGate(onActiveChange: (Boolean) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> onActiveChange(true)
                Lifecycle.Event.ON_PAUSE -> onActiveChange(false)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            onActiveChange(false)
        }
    }
}
