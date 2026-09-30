package org.autojs.autojs.permission

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.ui.AsrAccessibilityService
import com.brycewg.asrkb.store.Prefs
import org.autojs.autojs.core.permission.PermissionRequestActivity
import org.autojs.autojs.ui.main.drawer.PermissionItemHelper
import org.autojs.autojs.util.IntentUtils
import org.autojs.autojs.util.IntentUtils.startSafely

/**
 * BIBI (说点啥) permission entries for the host side drawer.
 *
 * Added by the bibi port on Sep 30, 2026.
 * The BiBi settings pages route their permission requests here via
 * `com.brycewg.asrkb.host.PermissionRouter`, so these helpers are the single
 * request entry for all non-overlapping BiBi permissions.
 *
 * zh-CN: 宿主侧边栏 BIBI 权限条目; BiBi 设置页内的权限申请动作经
 * `com.brycewg.asrkb.host.PermissionRouter` 路由至此, 统一管理.
 */

class BibiMicrophonePermission(override val context: Context) : PermissionItemHelper {

    override fun has() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun request(): Boolean {
        val intent = Intent(context, PermissionRequestActivity::class.java)
            .putExtra(PermissionRequestActivity.EXTRA_PERMISSIONS, arrayOf(Manifest.permission.RECORD_AUDIO))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.startSafely(context)
        return true
    }

    override fun revoke(): Boolean = false.also {
        // Runtime permissions cannot be revoked programmatically.
        // zh-CN: 运行时权限无法程序化撤销, 跳转应用详情页.
        IntentUtils.launchAppDetailsSettings(context)
    }

}

class BibiAccessibilityPermission(override val context: Context) : PermissionItemHelper {

    override fun has() = isBibiAccessibilityServiceEnabled()

    override fun request(): Boolean = false.also { config() }

    override fun revoke(): Boolean = false.also { config() }

    fun config() {
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .startSafely(context)
    }

    private fun isBibiAccessibilityServiceEnabled(): Boolean {
        val component = ComponentName(context, AsrAccessibilityService::class.java)
        val expectedComponentNames = setOf(component.flattenToString(), component.flattenToShortString())
        val enabledServicesSetting = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        } catch (t: Throwable) {
            return false
        }
        return enabledServicesSetting
            ?.split(':')
            ?.any { it in expectedComponentNames } == true
    }

}

class BibiBluetoothMicPermission(override val context: Context) : PermissionItemHelper {

    override fun has(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    override fun request(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val intent = Intent(context, PermissionRequestActivity::class.java)
            .putExtra(PermissionRequestActivity.EXTRA_PERMISSIONS, arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.startSafely(context)
        return true
    }

    override fun revoke(): Boolean = false.also {
        IntentUtils.launchAppDetailsSettings(context)
    }

}

/**
 * Not a system permission, but a plain switch gating the BiBi `BootReceiver` behavior.
 *
 * zh-CN: 非系统权限, 仅控制 BiBi `BootReceiver` 的开机拉起行为.
 */
class BibiBootAutostartPermission(override val context: Context) : PermissionItemHelper {

    override fun has() = Prefs(context).bootAutostartEnabled

    override fun request(): Boolean = true.also { Prefs(context).bootAutostartEnabled = true }

    override fun revoke(): Boolean = true.also { Prefs(context).bootAutostartEnabled = false }

}
