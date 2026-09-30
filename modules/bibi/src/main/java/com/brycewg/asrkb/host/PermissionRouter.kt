/**
 * Permission request router for the BIBI (说点啥) library inside a host application.
 *
 * 归属模块：宿主接线
 *
 * BiBi settings pages keep showing permission STATUS, but request ACTIONS are routed
 * to the host (AutoJs6 drawer "BIBI 权限" group) via this router. When the library runs
 * standalone (no host registered), a fallback jumps to the corresponding system settings
 * page so no button is ever dead.
 *
 * zh-CN: BiBi 各页面保留权限状态显示, 申请动作统一经此路由到宿主侧边栏权限区;
 * 库独立运行 (未注册宿主) 时兜底跳转系统设置页.
 */
package com.brycewg.asrkb.host

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

enum class BibiPermissionType {
    MICROPHONE,
    NOTIFICATIONS,
    OVERLAY,
    ACCESSIBILITY,
    BLUETOOTH_MIC,
}

interface BibiHostPermissionRouter {
    fun route(type: BibiPermissionType)
}

object PermissionRouter {

    @Volatile
    var host: BibiHostPermissionRouter? = null

    fun route(type: BibiPermissionType) {
        host?.route(type) ?: fallbackRoute(null, type)
    }

    fun route(context: Context, type: BibiPermissionType) {
        host?.route(type) ?: fallbackRoute(context, type)
    }

    private fun fallbackRoute(context: Context?, type: BibiPermissionType) {
        val ctx = context ?: return
        when (type) {
            BibiPermissionType.MICROPHONE,
            BibiPermissionType.BLUETOOTH_MIC,
            -> ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", ctx.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )

            BibiPermissionType.NOTIFICATIONS -> ctx.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )

            BibiPermissionType.OVERLAY -> ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    .setData(Uri.fromParts("package", ctx.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )

            BibiPermissionType.ACCESSIBILITY -> ctx.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
