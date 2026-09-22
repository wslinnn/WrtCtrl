package dev.wrtctrl.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * APK 下载与安装拉起：下载到 cache/updates，经 FileProvider 交给系统安装器
 * （ACTION_VIEW package-archive；安装未知来源应用的授权由系统面板引导）。
 * 下载地址再次过域校验（与检查阶段同一道门），不跟随非 GitHub 直链。
 */
object InAppUpdater {
    private const val TAG = "wrtctrl"

    enum class State { DOWNLOADING, DONE, FAILED }

    /** 进度：progress=null 表示总长未知（chunked 传输），UI 用不确定态 */
    data class DownloadProgress(
        val state: State,
        val progress: Float?,
        val error: String? = null,
    )

    suspend fun downloadAndInstall(
        context: Context,
        downloadUrl: String,
        fileName: String,
        onProgress: (DownloadProgress) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!UpdateChecker.isTrustedDownloadUrl(downloadUrl)) {
            Log.w(TAG, "reject untrusted download url")
            onProgress(DownloadProgress(State.FAILED, 0f, "untrusted url"))
            return@withContext false
        }
        try {
            onProgress(DownloadProgress(State.DOWNLOADING, 0f))
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val apk = File(dir, fileName)
            val conn = URL(downloadUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            val total = conn.contentLengthLong
            var done = 0L
            conn.inputStream.use { input ->
                FileOutputStream(apk).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        done += read
                        onProgress(
                            DownloadProgress(
                                State.DOWNLOADING,
                                if (total > 0) done.toFloat() / total else null,
                            ),
                        )
                    }
                }
            }
            conn.disconnect()
            install(context, apk)
            onProgress(DownloadProgress(State.DONE, 1f))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "apk download failed: ${e.message}")
            onProgress(DownloadProgress(State.FAILED, 0f, e.message))
            false
        }
    }

    /** 对已下载完成的 APK 重新拉起安装器（用户在系统面板取消后可从弹窗重试） */
    fun install(context: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
    }

    fun clearCache(context: Context) {
        File(context.cacheDir, "updates").listFiles()?.forEach { it.delete() }
    }

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val BUFFER_BYTES = 8192
}
