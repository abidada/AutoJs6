package com.brycewg.asrkb.ui

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/**
 * 宿主侧合并无障碍服务组件解析器。
 *
 * bibi 与宿主(app)合并为单一无障碍服务后, bibi 侧无法直接引用宿主服务类,
 * 组件名以 app 清单中的 meta-data `host_a11y_service_class` 为单一事实来源。
 */
object HostA11yServiceResolver {

    private const val META_HOST_SERVICE_CLASS = "host_a11y_service_class"

    /** 宿主合并无障碍服务的组件名; meta-data 缺失时返回 null。 */
    fun hostComponentName(context: Context): ComponentName? {
        val className = try {
            context.applicationInfo.metaData?.getString(META_HOST_SERVICE_CLASS)
        } catch (_: Throwable) {
            null
        } ?: return null
        if (className.isBlank()) return null
        return ComponentName(context.packageName, className)
    }

    /** 宿主合并无障碍服务是否已在系统设置中开启。 */
    fun isEnabledInSettings(context: Context): Boolean {
        val component = hostComponentName(context) ?: return false
        val expected = setOf(component.flattenToString(), component.flattenToShortString())
        val enabledServicesSetting = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        } catch (_: Throwable) {
            return false
        }
        return enabledServicesSetting
            ?.split(':')
            ?.any { it.trim() in expected } == true
    }
}
