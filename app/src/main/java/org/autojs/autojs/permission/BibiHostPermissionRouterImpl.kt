package org.autojs.autojs.permission

import com.brycewg.asrkb.host.BibiHostPermissionRouter
import com.brycewg.asrkb.host.BibiPermissionType
import org.autojs.autojs.app.GlobalAppContext

/**
 * Host-side implementation of the BIBI permission router.
 *
 * BiBi pages keep showing permission STATUS but delegate request ACTIONS here;
 * each action is handled by the corresponding host-side permission helper
 * (the same entries exposed in the main drawer "BIBI 权限" group).
 *
 * Added by the bibi port on Sep 30, 2026.
 */
object BibiHostPermissionRouterImpl : BibiHostPermissionRouter {

    override fun route(type: BibiPermissionType) {
        val context = GlobalAppContext.get()
        when (type) {
            BibiPermissionType.MICROPHONE -> BibiMicrophonePermission(context).requestIfNeeded()
            BibiPermissionType.NOTIFICATIONS -> PostNotificationsPermission(context).requestIfNeeded()
            BibiPermissionType.OVERLAY -> DisplayOverOtherAppsPermission(context).config()
            BibiPermissionType.ACCESSIBILITY -> BibiAccessibilityPermission(context).config()
            BibiPermissionType.BLUETOOTH_MIC -> BibiBluetoothMicPermission(context).requestIfNeeded()
        }
    }

}
