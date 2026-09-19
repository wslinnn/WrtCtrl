package dev.wrtctrl.net

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 本地网络权限门（Android 16 引入的 Local Network Protection）。
 *
 * targetSdk ≥ 36 的 app 访问私网（192.168/16、10/8、172.16/12、ULA 等）需要
 * 「附近的设备」权限——过渡期挂靠 NEARBY_WIFI_DEVICES（后续将换成专属权限）。
 * 未授权时发往局域网的 SYN 被静默黑洞，表现为 TCP 连接超时而非立即报错；
 * 蜂窝/公网/VPN 流量不在管制范围。
 *
 * 当前状态：targetSdk 钉在 35（豁免边界），本门休眠——
 * 部分 ROM 实测不认 NEARBY_WIFI_DEVICES 授权。恢复 targetSdk ≥36 前需确认
 * ROM 提供本地网络开关或 Android 专属权限落地（切回前需确认）。
 */
object LocalNetPermission {

    /** 目标是私网字面地址且尚未授权、且本 ROM 按 targetSdk 强制时返回 true——调用方应先弹系统权限请求 */
    fun needsRequest(context: Context, host: String): Boolean {
        // 36 = Android 16（LNP 引入档）；applicationInfo.targetSdkVersion 拿打包时实际 target
        if (Build.VERSION.SDK_INT < 36 || context.applicationInfo.targetSdkVersion < 36) return false
        val literal = NetBinder.parseIpLiteral(host) ?: return false
        if (!NetBinder.isPrivate(literal)) return false
        return ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) !=
            PackageManager.PERMISSION_GRANTED
    }
}
