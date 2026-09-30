package com.brycewg.asrkb.ui.floating

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.brycewg.asrkb.R
import com.brycewg.asrkb.host.BibiPermissionType
import com.brycewg.asrkb.host.PermissionRouter

internal class OverlayPermissionGate(
    private val context: Context,
    private val notifier: UserNotifier,
    private val tag: String
) {
    fun hasPermission(): Boolean = Settings.canDrawOverlays(context)

    fun showMissingPermissionToast() {
        try {
            notifier.showToast(context.getString(R.string.toast_need_overlay_perm))
        } catch (e: Throwable) {
            Log.w(tag, "Failed to show overlay permission toast", e)
        }
    }

    fun openSettings() {
        try {
            // 悬浮窗权限申请动作路由到宿主（无宿主时由 Router 兜底跳系统设置）
            PermissionRouter.route(context, BibiPermissionType.OVERLAY)
        } catch (e: Throwable) {
            Log.e(tag, "Failed to open overlay permission settings", e)
        }
    }

    fun requestPermission() {
        showMissingPermissionToast()
        openSettings()
    }
}
