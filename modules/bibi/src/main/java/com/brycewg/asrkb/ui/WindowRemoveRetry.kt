package com.brycewg.asrkb.ui

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager

private const val TAG = "WindowRemoveRetry"
private val mainHandler = Handler(Looper.getMainLooper())

/**
 * 悬浮窗拆除加固：removeView 失败（如 a11y 层 token 失效瞬间）时保留视图引用重试，
 * 避免调用方清空引用后 ViewRootImpl 永久滞留 WindowManagerGlobal。
 *
 * WindowManagerImpl 是进程级 WindowManagerGlobal 的薄封装，任意实例均可拆除同进程窗口。
 */
fun removeWindowViewWithRetry(wm: WindowManager, view: View, tag: String, maxRetries: Int = 3) {
    if (tryRemoveWindowView(wm, view, tag)) return
    var attempts = 0
    val retry = object : Runnable {
        override fun run() {
            // parent 为空说明已成功拆除（含失败但内部已 detach 的情况）
            if (view.parent == null) return
            if (tryRemoveWindowView(wm, view, tag)) return
            if (++attempts < maxRetries) {
                mainHandler.postDelayed(this, 200L)
            } else {
                Log.w(TAG, "Give up removing window after retries: $tag")
            }
        }
    }
    mainHandler.postDelayed(retry, 200L)
}

private fun tryRemoveWindowView(wm: WindowManager, view: View, tag: String): Boolean = try {
    wm.removeView(view)
    true
} catch (e: Throwable) {
    Log.w(TAG, "removeView failed for $tag", e)
    false
}
