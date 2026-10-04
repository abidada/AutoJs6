package org.autojs.autojs.runtime.api.augment.voice

import android.content.Intent
import com.brycewg.asrkb.host.AsrRecordingState
import com.brycewg.asrkb.host.AsrResultBroadcaster
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.floating.FloatingAsrService
import org.autojs.autojs.annotation.RhinoRuntimeFunctionInterface
import org.autojs.autojs.rhino.ArgumentGuards
import org.autojs.autojs.rhino.ArgumentGuards.Companion.component1
import org.autojs.autojs.runtime.ScriptRuntime
import org.autojs.autojs.runtime.api.augment.Augmentable
import org.autojs.autojs.util.RhinoUtils.UNDEFINED
import org.autojs.autojs.util.RhinoUtils.callFunction
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Undefined
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * BIBI (说点啥) voice recognition API for scripts.
 *
 * Available as `bibi` and `$bibi` in the JS environment (Augmentable naming rule).
 * Recognition results are also printed into the global console by the host-side
 * bridge registered in `com.brycewg.asrkb.host.BibiLibrary.init`.
 *
 * JS usage:
 * ```
 * $bibi.onResult(t => console.log("final:", t));
 * $bibi.onPartial(t => console.log("partial:", t));
 * $bibi.start();   // start recording via the floating ball service
 * $bibi.stop();    // stop recording
 * $bibi.isRecording();
 * $bibi.showBall(); $bibi.hideBall();
 * $bibi.hasMicPermission();
 * ```
 *
 * Added by the bibi port on Sep 30, 2026.
 */
@Suppress("unused")
class Bibi(scriptRuntime: ScriptRuntime) : Augmentable(scriptRuntime) {

    override val selfAssignmentFunctions = listOf(
        ::onResult.name,
        ::onPartial.name,
        ::offResult.name,
        ::offPartial.name,
        ::start.name,
        ::stop.name,
        ::isRecording.name,
        ::showBall.name,
        ::hideBall.name,
        ::hasMicPermission.name,
    )

    init {
        ensureResultListenerRegistered()
    }

    companion object : ArgumentGuards() {

        private val mFinalCallbacks =
            Collections.synchronizedMap(WeakHashMap<ScriptRuntime, MutableList<BaseFunction>>())
        private val mPartialCallbacks =
            Collections.synchronizedMap(WeakHashMap<ScriptRuntime, MutableList<BaseFunction>>())

        @Volatile
        private var mIsResultListenerRegistered = false

        private fun ensureResultListenerRegistered() {
            if (mIsResultListenerRegistered) return
            synchronized(this) {
                if (mIsResultListenerRegistered) return
                AsrResultBroadcaster.add(object : AsrResultBroadcaster.Listener {
                    override fun onPartial(text: String) = dispatch(mPartialCallbacks, text)
                    override fun onFinal(text: String) = dispatch(mFinalCallbacks, text)
                    override fun onError(msg: String) = Unit
                })
                mIsResultListenerRegistered = true
            }
        }

        private fun dispatch(
            table: MutableMap<ScriptRuntime, MutableList<BaseFunction>>,
            text: String,
        ) {
            val snapshot = synchronized(table) { table.entries.toList() }
            snapshot.forEach { (scriptRuntime, functions) ->
                functions.forEach { function ->
                    scriptRuntime.uiHandler.post {
                        runCatching {
                            callFunction(scriptRuntime, function, arrayOf<Any?>(text))
                        }
                    }
                }
            }
        }

        @JvmStatic
        @RhinoRuntimeFunctionInterface
        fun onResult(scriptRuntime: ScriptRuntime, args: Array<out Any?>): Undefined =
            ensureArgumentsAtLeast(args, 1) { argList ->
                val (function) = argList
                require(function is BaseFunction) { "Argument for \$bibi.onResult must be a function" }
                val list = mFinalCallbacks.getOrPut(scriptRuntime) { CopyOnWriteArrayList() }
                // 幂等添加：脚本循环里重复注册同一函数时避免列表无限增长
                if (!list.contains(function)) list.add(function)
                UNDEFINED
            }

        @JvmStatic
        @RhinoRuntimeFunctionInterface
        fun onPartial(scriptRuntime: ScriptRuntime, args: Array<out Any?>): Undefined =
            ensureArgumentsAtLeast(args, 1) { argList ->
                val (function) = argList
                require(function is BaseFunction) { "Argument for \$bibi.onPartial must be a function" }
                val list = mPartialCallbacks.getOrPut(scriptRuntime) { CopyOnWriteArrayList() }
                if (!list.contains(function)) list.add(function)
                UNDEFINED
            }

        @JvmStatic
        @RhinoRuntimeFunctionInterface
        fun offResult(scriptRuntime: ScriptRuntime, args: Array<out Any?>): Undefined =
            ensureArgumentsIsEmpty(args) {
                mFinalCallbacks.remove(scriptRuntime)
                UNDEFINED
            }

        @JvmStatic
        @RhinoRuntimeFunctionInterface
        fun offPartial(scriptRuntime: ScriptRuntime, args: Array<out Any?>): Undefined =
            ensureArgumentsIsEmpty(args) {
                mPartialCallbacks.remove(scriptRuntime)
                UNDEFINED
            }

        @JvmStatic
        @RhinoRuntimeFunctionInterface
        fun start(scriptRuntime: ScriptRuntime, args: Array<out Any?>): Undefined =
            ensureArgumentsIsEmpty(args) {
                globalContext.startService(
                    Intent(globalContext, FloatingAsrService::class.java)
                        .setAction(FloatingAsrService.ACTION_SCRIPT_START)
                )
                UNDEFINED
            }

        @JvmStatic
        @RhinoRuntimeFunctionInterface
        fun stop(scriptRuntime: ScriptRuntime, args: Array<out Any?>): Undefined =
            ensureArgumentsIsEmpty(args) {
                globalContext.startService(
                    Intent(globalContext, FloatingAsrService::class.java)
                        .setAction(FloatingAsrService.ACTION_SCRIPT_STOP)
                )
                UNDEFINED
            }

        @JvmStatic
        @RhinoRuntimeFunctionInterface
        fun isRecording(scriptRuntime: ScriptRuntime, args: Array<out Any?>): Boolean =
            ensureArgumentsIsEmpty(args) {
                AsrRecordingState.active
            }

        @JvmStatic
        @RhinoRuntimeFunctionInterface
        fun showBall(scriptRuntime: ScriptRuntime, args: Array<out Any?>): Undefined =
            ensureArgumentsIsEmpty(args) {
                Prefs(globalContext).floatingAsrEnabled = true
                globalContext.startService(
                    Intent(globalContext, FloatingAsrService::class.java)
                        .setAction(FloatingAsrService.ACTION_SHOW)
                )
                UNDEFINED
            }

        @JvmStatic
        @RhinoRuntimeFunctionInterface
        fun hideBall(scriptRuntime: ScriptRuntime, args: Array<out Any?>): Undefined =
            ensureArgumentsIsEmpty(args) {
                globalContext.startService(
                    Intent(globalContext, FloatingAsrService::class.java)
                        .setAction(FloatingAsrService.ACTION_HIDE)
                )
                UNDEFINED
            }

        @JvmStatic
        @RhinoRuntimeFunctionInterface
        fun hasMicPermission(scriptRuntime: ScriptRuntime, args: Array<out Any?>): Boolean =
            ensureArgumentsIsEmpty(args) {
                ContextCompat.checkSelfPermission(globalContext, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
            }

        /**
         * Cleanup hook invoked from `ScriptRuntime.onExit()`.
         * zh-CN: 脚本运行时退出时清理回调表, 由 `ScriptRuntime.onExit()` 调用.
         */
        @JvmStatic
        fun onScriptRuntimeExit(scriptRuntime: ScriptRuntime) {
            mFinalCallbacks.remove(scriptRuntime)
            mPartialCallbacks.remove(scriptRuntime)
        }

    }

}
