package org.autojs.autojs.host

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.ai.assistance.operit.services.FloatingChatService
import org.autojs.autojs.AutoJs

/**
 * Operit 悬浮窗与宿主主界面的协调器（悬浮窗联动批2 / F5）。
 *
 * 职责：监听 Operit 悬浮窗服务的生命周期广播，在「悬浮窗窗口已展示」且
 * 「当前前台是宿主主界面」两个条件同时成立时，把主界面退到后台，避免它压在
 * 悬浮窗底下。与模块侧 FloatingWindowDelegate 的自愿退让链路互为补充——
 * 本协调器不依赖主界面 ViewModel 存活，覆盖从语音唤醒/工作流/设置页等
 * 非主界面入口启动悬浮窗的场景。
 *
 * 前台 Activity 判定复用 AbstractAutoJs 已注册的 lifecycle 回调
 * （AppUtils.currentActivity），不额外挂第二套监听。
 *
 * 归属模块：host
 */
object OperitFloatingCoordinator {

    @Volatile
    private var registered: Boolean = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                FloatingChatService.ACTION_FLOATING_CHAT_WINDOW_SHOWN -> moveHostMainActivityToBackIfForeground()
                else -> Unit
            }
        }
    }

    fun register(appContext: Context) {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(FloatingChatService.ACTION_FLOATING_CHAT_WINDOW_SHOWN)
            addAction(FloatingChatService.ACTION_FLOATING_CHAT_WINDOW_SHOW_FAILED)
            addAction(FloatingChatService.ACTION_FLOATING_CHAT_SERVICE_STOPPED)
            addAction(FloatingChatService.ACTION_FLOATING_CHAT_MODE_CHANGED)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(receiver, filter)
            }
            registered = true
        } catch (_: Exception) {
        }
    }

    /**
     * 仅当宿主主界面正处前台时才退后台：悬浮窗可能从任意应用上层展开
     * （语音唤醒等），此时前台不是宿主界面，绝不能干扰第三方应用。
     */
    private fun moveHostMainActivityToBackIfForeground() {
        val autoJs = runCatching { AutoJs.instance }.getOrNull() ?: return
        val activity = autoJs.appUtils.currentActivity ?: return
        if (!activity.isFinishing && !activity.isDestroyed) {
            runCatching { activity.moveTaskToBack(true) }
        }
    }
}
