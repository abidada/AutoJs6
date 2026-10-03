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

    /**
     * 无障碍可用性统一判定(bibi 所有检测点的唯一入口):
     * 系统设置串已启用 或 核心已连接(服务实际在跑)。
     * 两者取或的原因: 部分 ROM(如 Honor/MagicOS)进出无障碍设置页时会重写
     * enabled_accessibility_services, 可能清掉/改写我们条目, 而服务实际仍在绑定运行;
     * 反之冷启动时设置串已启用但绑定未完成, 需以设置串为准。
     */
    fun isA11yEnabled(context: Context): Boolean =
        isEnabledInSettings(context) || AsrAccessibilityService.isEnabled()
}
