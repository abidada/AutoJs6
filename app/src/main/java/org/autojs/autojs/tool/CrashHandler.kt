package org.autojs.autojs.tool

import android.content.Intent
import android.os.Looper
import android.util.Log
import org.autojs.autojs.app.GlobalAppContext
import org.autojs.autojs.runtime.ScriptRuntime
import org.autojs.autojs.util.IntentUtils.startSafely
import java.lang.Thread.UncaughtExceptionHandler
import java.lang.ref.WeakReference
import kotlin.system.exitProcess

/**
 * Created by Stardust on Feb 2, 2017.
 * Modified by SuperMonster003 as of Dec 1, 2021.
 * Transformed by SuperMonster003 on Nov 28, 2023.
 */
class CrashHandler(private val errorReportClass: Class<*>) : UncaughtExceptionHandler {

    private val mSystemHandler: UncaughtExceptionHandler? by lazy {
        Thread.getDefaultUncaughtExceptionHandler()
    }

    private var mCachedExceptionMessage: MutableList<WeakReference<Throwable>> = ArrayList()

    override fun uncaughtException(thread: Thread, ex: Throwable) {
        Log.e(TAG, "Uncaught Exception", ex)
        // ScriptRuntime.popException(ex.message ?: "Uncaught Exception")
        val latestMessage = ex.message ?: "[ No error message ]"
        if (mCachedExceptionMessage.size > 4) {
            if (mCachedExceptionMessage.all { it.get()?.message == latestMessage } || mCachedExceptionMessage.size > 20) {
                ScriptRuntime.popException(latestMessage)
                startCrashReportActivity("Uncaught Exception", ex.stackTraceToString())
                exitProcess(1)
            }
        }
        mCachedExceptionMessage.add(WeakReference(ex))
        if (thread != Looper.getMainLooper().thread) {
            return
        }
        // zh-CN: 合并版无障碍服务同时承载语音悬浮球等常驻能力, 且目标用户不具备
        // WRITE_SECURE_SETTINGS, disableSelf 关闭的开关只能手动恢复; 进程崩溃本身
        // 已断开服务, 故此处不再 disableSelf(否则一次脚本崩溃会导致语音功能瘫痪
        // 到用户手动重开). 崩溃循环防护由上方重复崩溃 exitProcess 兜底.
        mSystemHandler?.uncaughtException(thread, ex)
    }

    private fun startCrashReportActivity(msg: String, detail: String) {
        val context = GlobalAppContext.get()
        Intent(context, errorReportClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("message", msg)
            putExtra("error", detail)
        }.startSafely(context)
    }

    companion object {
        private const val TAG = "CrashHandler"
    }

}