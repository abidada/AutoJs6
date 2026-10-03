package org.autojs.autojs.core.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.brycewg.asrkb.ui.AsrAccessibilityService
import org.autojs.autojs.core.pref.Pref
import org.autojs.autojs.core.accessibility.AccessibilityService as CoreAccessibilityService

/**
 * 合并版无障碍服务: 同时承载 AutoJs6 脚本自动化引擎与 bibi 语音识别核心.
 * 本应用在系统无障碍列表中仅此一个服务, 开启后两条能力线同时生效.
 */
class AccessibilityServiceUsher : CoreAccessibilityService() {

    /** bibi 语音识别核心: 悬浮球文本写入/IME 显隐检测/音量键摇一摇触发/无障碍层窗口宿主. */
    private var asrCore: AsrAccessibilityService? = null

    override fun onServiceConnected() {
        Log.d(TAG, "onServiceConnected")
        val serviceInfo = serviceInfo
        if (Pref.isStableModeEnabled) {
            serviceInfo.flags = serviceInfo.flags and AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS.inv()
        } else {
            serviceInfo.flags = serviceInfo.flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (Pref.isGestureObservingEnabled) {
                serviceInfo.flags = serviceInfo.flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            } else {
                serviceInfo.flags = serviceInfo.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE.inv()
            }
        }
        setServiceInfo(serviceInfo)
        super.onServiceConnected()
        // 重复连接时先拆旧核心, 避免传感器监听与悬浮层宿主滞留
        asrCore?.onDetached()
        asrCore = AsrAccessibilityService(this).also { it.onConnected() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        super.onAccessibilityEvent(event)
        asrCore?.onAccessibilityEvent(event)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // bibi 侧仅在音量键录音开启且 IME 场景活跃时消费, 非音量键立即返回, 先判代价极低
        val consumedByAsr = asrCore?.onKeyEvent(event) ?: false
        val interceptedByAutoJs = super.onKeyEvent(event)
        return consumedByAsr || interceptedByAutoJs
    }

    override fun onDestroy() {
        asrCore?.onDetached()
        asrCore = null
        super.onDestroy()
    }

    companion object {

        private val TAG = AccessibilityServiceUsher::class.java.simpleName

    }

}
