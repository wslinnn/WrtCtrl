package dev.wrtctrl.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.wrtctrl.update.InAppUpdater
import dev.wrtctrl.update.UpdateChecker
import dev.wrtctrl.update.UpdateInfo
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 应用内更新状态机（手动检查，应用页入口）：
 * Idle → Checking → Latest / Failed / Available → Downloading → ReadyToInstall。
 * VM 挂 Activity 作用域：下载中切 Tab 弹窗关闭但下载继续，回到应用页重开弹窗可见进度。
 */
class UpdateViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface UiState {
        data object Idle : UiState
        data object Checking : UiState
        data object Latest : UiState

        /** 原始错误只进 logcat，上屏走分类文案 */
        data object CheckFailed : UiState
        data object DownloadFailed : UiState
        data class Available(val info: UpdateInfo) : UiState
        data class Downloading(val info: UpdateInfo, val progress: Float?) : UiState
        data class ReadyToInstall(val info: UpdateInfo) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var downloadJob: Job? = null

    /** 已完成的安装包（ReadyToInstall 重试拉安装器用；文件在 cache，系统回收后回退重下载） */
    private var downloadedApk: File? = null

    fun currentVersionName(): String =
        getApplication<Application>().packageManager
            .getPackageInfo(getApplication<Application>().packageName, 0).versionName ?: "--"

    fun check() {
        if (_state.value is UiState.Checking || _state.value is UiState.Downloading) return
        _state.value = UiState.Checking
        viewModelScope.launch {
            val result = UpdateChecker.checkForUpdate(currentVersionName())
            _state.value = when {
                result.update != null -> UiState.Available(result.update)
                result.error != null -> UiState.CheckFailed
                else -> UiState.Latest
            }
        }
    }

    fun download() {
        val current = _state.value as? UiState.Available ?: return
        clearCache()
        _state.value = UiState.Downloading(current.info, 0f)
        downloadJob = viewModelScope.launch {
            val ok = InAppUpdater.downloadAndInstall(
                getApplication(),
                current.info.downloadUrl,
                "wrtctrl-${current.info.versionName}.apk",
            ) { progress ->
                when (progress.state) {
                    InAppUpdater.State.DOWNLOADING ->
                        if (_state.value is UiState.Downloading) {
                            _state.value = UiState.Downloading(current.info, progress.progress)
                        }
                    InAppUpdater.State.DONE -> {
                        downloadedApk =
                            File(getApplication<Application>().cacheDir, "updates/wrtctrl-${current.info.versionName}.apk")
                        _state.value = UiState.ReadyToInstall(current.info)
                    }
                    InAppUpdater.State.FAILED -> _state.value = UiState.DownloadFailed
                }
            }
            if (!ok && _state.value is UiState.Downloading) {
                _state.value = UiState.Available(current.info)
            }
        }
    }

    /** 已就绪的安装包重试拉起安装器；缓存文件已被系统回收时回退 Available 重下载 */
    fun install() {
        val current = _state.value as? UiState.ReadyToInstall ?: return
        val apk = downloadedApk
        if (apk != null && apk.exists()) {
            InAppUpdater.install(getApplication(), apk)
        } else {
            _state.value = UiState.Available(current.info)
        }
    }

    /** 下载中取消：中断请求回到 Available（可重新发起） */
    fun cancelDownload() {
        if (_state.value !is UiState.Downloading) return
        downloadJob?.cancel()
        downloadJob = null
        clearCache()
        val info = (_state.value as? UiState.Downloading)?.info ?: return
        _state.value = UiState.Available(info)
    }

    /** 弹窗关闭归位（下载不受影响） */
    fun dismiss() {
        if (_state.value is UiState.Downloading) return
        _state.value = UiState.Idle
    }

    private fun clearCache() {
        InAppUpdater.clearCache(getApplication())
    }
}
