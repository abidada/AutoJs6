/**
 * 悬浮窗挂载层：决定悬浮球/菜单/面板窗口挂在哪个 WindowManager 与窗口类型上。
 *
 * - 普通层：TYPE_APPLICATION_OVERLAY（默认；受前台应用 setHideOverlayWindows 影响，
 *   在荣耀系统设置等防遮挡界面上会被系统隐藏）；
 * - 无障碍层：TYPE_ACCESSIBILITY_OVERLAY（由 AsrAccessibilityService 提供托管的
 *   WindowManager；不受 setHideOverlayWindows 影响，可常驻显示在一切应用上层）。
 *
 * 归属模块：ui/floatingball
 */
package com.brycewg.asrkb.ui.floatingball

import android.content.Context
import android.view.WindowManager

class FloatingWindowHost(
    val context: Context,
    val windowManager: WindowManager,
    val windowType: Int
) {
    /** 是否与 [other] 处于同一挂载层（同 WindowManager 实例且同窗口类型）。 */
    fun sameLayerAs(other: FloatingWindowHost?): Boolean =
        other != null &&
            other.windowType == windowType &&
            other.windowManager === windowManager
}
