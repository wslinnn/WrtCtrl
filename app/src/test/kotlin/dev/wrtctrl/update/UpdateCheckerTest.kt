package dev.wrtctrl.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    // ---------- isNewerVersion：v 前缀 / -dev 后缀 / 逐段整数比较 ----------

    @Test
    fun dev_suffix_equals_release_tag() {
        assertFalse(UpdateChecker.isNewerVersion("1.0.0-dev", "1.0.0"))
        assertFalse(UpdateChecker.isNewerVersion("1.0.0-dev", "v1.0.0"))
    }

    @Test
    fun newer_patch_and_minor_detected() {
        assertTrue(UpdateChecker.isNewerVersion("1.0.0", "1.0.1"))
        assertTrue(UpdateChecker.isNewerVersion("1.0.0", "1.1.0"))
        assertTrue(UpdateChecker.isNewerVersion("1.0.0", "2.0.0"))
    }

    @Test
    fun older_or_equal_not_detected() {
        assertFalse(UpdateChecker.isNewerVersion("1.1.0", "1.0.9"))
        assertFalse(UpdateChecker.isNewerVersion("1.0.0", "1.0.0"))
        assertFalse(UpdateChecker.isNewerVersion("1.0.1", "1.0.0"))
    }

    @Test
    fun numeric_not_lexicographic() {
        assertFalse(UpdateChecker.isNewerVersion("1.10.0", "1.9.0"))
        assertTrue(UpdateChecker.isNewerVersion("1.9.0", "1.10.0"))
    }

    @Test
    fun missing_segment_pads_zero() {
        assertFalse(UpdateChecker.isNewerVersion("1.0", "1.0.0"))
        assertTrue(UpdateChecker.isNewerVersion("1.0.0", "1.1"))
    }

    // ---------- isTrustedDownloadUrl：仅 https + GitHub 域 ----------

    @Test
    fun github_https_allowed() {
        assertTrue(
            UpdateChecker.isTrustedDownloadUrl(
                "https://github.com/wslinnn/WrtCtrl/releases/download/v1.0.1/wrtctrl-1.0.1.apk",
            ),
        )
        assertTrue(
            UpdateChecker.isTrustedDownloadUrl(
                "https://objects.githubusercontent.com/assets/abc/wrtctrl.apk?token=x",
            ),
        )
    }

    @Test
    fun non_github_or_plain_http_rejected() {
        assertFalse(UpdateChecker.isTrustedDownloadUrl("http://github.com/a/wrtctrl.apk"))
        assertFalse(UpdateChecker.isTrustedDownloadUrl("https://evil.example/wrtctrl.apk"))
        assertFalse(UpdateChecker.isTrustedDownloadUrl("https://192.168.1.1/wrtctrl.apk"))
        assertFalse(UpdateChecker.isTrustedDownloadUrl("https://127.0.0.1/wrtctrl.apk"))
        // 伪装域：github.com.evil.com / user-info 截断均不落入 allowlist
        assertFalse(UpdateChecker.isTrustedDownloadUrl("https://github.com.evil.com/wrtctrl.apk"))
        assertFalse(UpdateChecker.isTrustedDownloadUrl("https://github.com@evil.com/wrtctrl.apk"))
        assertFalse(UpdateChecker.isTrustedDownloadUrl("not a url"))
        assertFalse(UpdateChecker.isTrustedDownloadUrl(""))
    }

    // ---------- parseLatestRelease：资产挑选 / 回退链接 / draft 守卫 ----------

    @Test
    fun picks_apk_asset_with_size() {
        val json = """
            {
              "tag_name": "v1.1.0",
              "draft": false,
              "body": "- fix wifi edit",
              "assets": [
                {"name": "checksums.txt", "browser_download_url": "https://github.com/a/checksums.txt", "size": 64},
                {"name": "wrtctrl-1.1.0.apk", "browser_download_url": "https://github.com/a/wrtctrl-1.1.0.apk", "size": 5242880}
              ]
            }
        """.trimIndent()
        val info = UpdateChecker.parseLatestRelease(json)
        assertNotNull(info)
        assertEquals("1.1.0", info!!.versionName)
        assertEquals("- fix wifi edit", info.changelog)
        assertTrue(info.downloadUrl.endsWith("wrtctrl-1.1.0.apk"))
        assertEquals(5242880L, info.apkSize)
    }

    @Test
    fun falls_back_to_release_page_without_apk_asset() {
        val json = """
            {
              "tag_name": "v1.1.0",
              "draft": false,
              "body": "",
              "assets": []
            }
        """.trimIndent()
        val info = UpdateChecker.parseLatestRelease(json)
        assertNotNull(info)
        assertEquals(0L, info!!.apkSize)
        assertEquals("https://github.com/${UpdateChecker.REPO}/releases/tag/v1.1.0", info.downloadUrl)
    }

    @Test
    fun draft_or_missing_tag_is_no_release() {
        assertNull(
            UpdateChecker.parseLatestRelease("""{"tag_name": "v1.1.0", "draft": true, "assets": []}"""),
        )
        assertNull(UpdateChecker.parseLatestRelease("""{"draft": false, "assets": []}"""))
    }
}
