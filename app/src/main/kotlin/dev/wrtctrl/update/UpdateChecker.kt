package dev.wrtctrl.update

import android.util.Log
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 最新版本信息（releases/latest 的最小消费集） */
data class UpdateInfo(
    val tagName: String,
    val versionName: String,
    val changelog: String,
    val downloadUrl: String,
    val apkSize: Long,
)

/** 检查结果：update=null 且 error=null 表示已是最新 */
data class CheckResult(
    val update: UpdateInfo?,
    val error: String?,
)

/**
 * 应用内更新的版本检查（GitHub Releases）：
 * releases/latest 拿最新 tag 与 changelog，资产里取首个 .apk 的直链；无 .apk 资产时
 * downloadUrl 回退发布页（apkSize=0，UI 据此改为「打开发布页」）。下载地址仅接受
 * https + GitHub 域——API 响应里的地址同样过这道校验，不跟进任意第三方链接。
 */
object UpdateChecker {
    private const val TAG = "wrtctrl"

    /** 建远端仓库后若实际路径不同，此处与 README 的 Releases 链接需同步改 */
    const val REPO = "wslinnn/WrtCtrl"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    suspend fun checkForUpdate(currentVersionName: String): CheckResult = withContext(Dispatchers.IO) {
        try {
            val conn = URL(API_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "WrtCtrl/$currentVersionName")
            val code = conn.responseCode
            if (code != HTTP_OK) {
                conn.disconnect()
                return@withContext CheckResult(null, "HTTP $code")
            }
            val json = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val info = parseLatestRelease(json) ?: return@withContext CheckResult(null, null)
            if (isNewerVersion(currentVersionName, info.versionName)) {
                CheckResult(info, null)
            } else {
                CheckResult(null, null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "update check failed: ${e.message}")
            CheckResult(null, e.message ?: "network")
        }
    }

    /** 解析 releases/latest 响应体；tag 缺失或 draft 视为无版本 */
    internal fun parseLatestRelease(json: String): UpdateInfo? {
        val obj = JSONObject(json)
        if (obj.optBoolean("draft", false)) return null
        val tagName = obj.optString("tag_name", "")
        if (tagName.isEmpty()) return null
        var downloadUrl = "https://github.com/$REPO/releases/tag/$tagName"
        var apkSize = 0L
        val assets = obj.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val url = asset.optString("browser_download_url", "")
                if (url.endsWith(".apk", ignoreCase = true) && isTrustedDownloadUrl(url)) {
                    downloadUrl = url
                    apkSize = asset.optLong("size", 0L)
                    break
                }
            }
        }
        return UpdateInfo(
            tagName = tagName,
            versionName = tagName.removePrefix("v"),
            changelog = obj.optString("body", ""),
            downloadUrl = downloadUrl,
            apkSize = apkSize,
        )
    }

    /** 版本段比较：去 v 前缀与 -dev 后缀后逐段整数比，缺段补 0（"1.0.0-dev" 与 "v1.0.0" 等价） */
    internal fun isNewerVersion(current: String, latest: String): Boolean {
        val cur = versionSegments(current)
        val lat = versionSegments(latest)
        for (i in 0 until maxOf(cur.size, lat.size)) {
            val c = cur.getOrElse(i) { 0 }
            val l = lat.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    private fun versionSegments(version: String): List<Int> =
        version.removePrefix("v").removeSuffix("-dev").split(".").mapNotNull { it.toIntOrNull() }

    /** 仅放行 https + GitHub 域（github.com / *.github.com / *.githubusercontent.com） */
    internal fun isTrustedDownloadUrl(raw: String): Boolean = try {
        val url = URI(raw)
        val host = url.host?.lowercase() ?: return false
        url.scheme == "https" &&
            (
                host == "github.com" ||
                    host.endsWith(".github.com") ||
                    host.endsWith(".githubusercontent.com")
                )
    } catch (e: Exception) {
        false
    }

    private const val HTTP_OK = 200
}
