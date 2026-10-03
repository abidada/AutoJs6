/**
 * 语音分发执行面（v2）：命中规则后按分发类型转发到真实执行链路。
 *
 * 设计原则（定稿方案）：分发层只做「选择 + 转发」，真实执行走宿主既有流程；
 * 本期仅接通 SCRIPT 立即执行（与文件列表运行按钮同链路），其余类型保持 v1 日志桩。
 * 整体错误隔离：任何异常不影响识别、续听与悬浮球（§7.3 结论）。
 *
 * 归属模块：host/voice
 */
package com.brycewg.asrkb.host.voice

import android.content.Context
import android.util.Log
import com.brycewg.asrkb.R
import com.brycewg.asrkb.host.BibiHostScriptBridge
import com.brycewg.asrkb.host.ScriptHost
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager

internal object VoiceDispatchExecutor {

    private const val TAG = "VoiceDispatch"

    /**
     * 执行结果反馈槽（交互层注入，回调在调用方线程）。
     * message 为空 = 成功且无需反馈（立即执行成功保持静默，与运行按钮一致）。
     */
    @Volatile
    var onResult: ((rule: VoiceDispatchRule, ok: Boolean, message: String?) -> Unit)? = null

    /**
     * 测试执行结果槽（规则管理页「测试」按钮注入；与语音链路互不干扰）。
     * 测试触发不写规则统计（不经过 maybeDispatch）。
     */
    @Volatile
    var onTestResult: ((rule: VoiceDispatchRule, ok: Boolean, message: String?) -> Unit)? = null

    /** 定时任务绑定写回（分发器注入：写规则存储 + 失效缓存），语音重建任务后更新绑定 id。 */
    @Volatile
    var bindTimedTask: ((ruleId: String, taskId: Long) -> Unit)? = null

    fun execute(context: Context, rule: VoiceDispatchRule, args: String) {
        try {
            dispatch(context, rule, args, onResult)
        } catch (t: Throwable) {
            Log.e(TAG, "dispatch execute failed: rule=${rule.name}", t)
        }
    }

    /** 规则管理页「测试」直连执行：与语音命中同一执行面，结果走 [onTestResult]。 */
    fun executeForTest(context: Context, rule: VoiceDispatchRule) {
        try {
            dispatch(context, rule, "", onTestResult)
        } catch (t: Throwable) {
            Log.e(TAG, "dispatch test execute failed: rule=${rule.name}", t)
        }
    }

    /** 统一结果出口：结果槽回调 + 持久日志（B5 排障口径）。 */
    private fun report(
        context: Context,
        rule: VoiceDispatchRule,
        ok: Boolean,
        message: String?,
        sink: ((rule: VoiceDispatchRule, ok: Boolean, message: String?) -> Unit)?
    ) {
        try {
            DebugLogManager.logPersistent(
                context,
                "dispatch",
                "executed",
                data = mapOf(
                    "type" to rule.dispatchType.name,
                    "mode" to rule.scriptExecMode.name,
                    "rule" to rule.name,
                    "path" to rule.payload.takeLast(96),
                    "ok" to ok
                )
            )
        } catch (_: Throwable) {
        }
        sink?.invoke(rule, ok, message)
    }

    private fun dispatch(
        context: Context,
        rule: VoiceDispatchRule,
        args: String,
        sink: ((rule: VoiceDispatchRule, ok: Boolean, message: String?) -> Unit)?
    ) {
        when (rule.dispatchType) {
            VoiceDispatchType.SCRIPT -> executeScript(context, rule, sink)

            // 其余三型为 v2 后续阶段接入点，保持日志桩
            VoiceDispatchType.AUTOMATION,
            VoiceDispatchType.NATIVE,
            VoiceDispatchType.ANDROID_API ->
                Log.i(TAG, "stub: type=${rule.dispatchType} rule=${rule.name} payload=${rule.payload} args=$args")
        }
    }

    private fun executeScript(
        context: Context,
        rule: VoiceDispatchRule,
        sink: ((rule: VoiceDispatchRule, ok: Boolean, message: String?) -> Unit)?
    ) {
        val bridge = ScriptHost.bridge
        if (bridge == null) {
            Log.w(TAG, "no host script bridge registered; skip script execution")
            report(context, rule, false, context.getString(R.string.voice_dispatch_script_failed), sink)
            return
        }
        when (rule.scriptExecMode) {
            ScriptExecMode.IMMEDIATE -> executeImmediate(context, rule, bridge, sink)
            ScriptExecMode.LOOP -> executeLoop(context, rule, bridge, sink)
            ScriptExecMode.TIMED -> executeTimed(context, rule, bridge, sink)
        }
    }

    /** 立即执行：与运行按钮同链路；成功静默。 */
    private fun executeImmediate(
        context: Context,
        rule: VoiceDispatchRule,
        bridge: BibiHostScriptBridge,
        sink: ((rule: VoiceDispatchRule, ok: Boolean, message: String?) -> Unit)?
    ) {
        if (!bridge.exists(rule.payload)) {
            Log.w(TAG, "script not found: ${rule.payload}")
            report(context, rule, false, context.getString(R.string.voice_dispatch_script_missing), sink)
            return
        }
        val ok = bridge.runScript(rule.payload)
        Log.i(TAG, "script executed: ok=$ok path=${rule.payload}")
        report(
            context,
            rule,
            ok,
            if (ok) null else context.getString(R.string.voice_dispatch_script_failed),
            sink
        )
    }

    /**
     * 循环运行：与「循环运行」弹窗确认同链路。
     * 拒绝策略下已在跑则提示并不执行；允许策略下与手动连点一致直接再起一条。
     */
    private fun executeLoop(
        context: Context,
        rule: VoiceDispatchRule,
        bridge: BibiHostScriptBridge,
        sink: ((rule: VoiceDispatchRule, ok: Boolean, message: String?) -> Unit)?
    ) {
        if (!bridge.exists(rule.payload)) {
            Log.w(TAG, "script not found: ${rule.payload}")
            report(context, rule, false, context.getString(R.string.voice_dispatch_script_missing), sink)
            return
        }
        if (Prefs(context).voiceDispatchDuplicatePolicy == Prefs.VoiceDuplicatePolicy.REFUSE &&
            bridge.isScriptRunning(rule.payload)
        ) {
            Log.i(TAG, "loop refused: already running path=${rule.payload}")
            report(context, rule, false, context.getString(R.string.voice_dispatch_loop_running), sink)
            return
        }
        val ok = bridge.runRepeatedly(
            rule.payload,
            rule.loopTimes,
            rule.loopDelayMs,
            rule.loopIntervalMs
        )
        Log.i(TAG, "loop started: ok=$ok path=${rule.payload} times=${rule.loopTimes}")
        report(
            context,
            rule,
            ok,
            when {
                ok -> context.getString(R.string.voice_dispatch_loop_started)
                else -> context.getString(R.string.voice_dispatch_script_failed)
            },
            sink
        )
    }

    /**
     * 定时任务：按规则快照创建/调度（与文件列表「定时任务」同链路，Alarm 调度、重启恢复）。
     * 拒绝策略下已存在相同（路径+时间+重复标志）任务则提示并不重复设置；
     * 允许策略下与手动重复保存一致再建一条。任务被用户删除后可经语音重建。
     */
    private fun executeTimed(
        context: Context,
        rule: VoiceDispatchRule,
        bridge: BibiHostScriptBridge,
        sink: ((rule: VoiceDispatchRule, ok: Boolean, message: String?) -> Unit)?
    ) {
        val snapshot = rule.timedTaskSnapshot
        if (snapshot == null) {
            Log.w(TAG, "timed rule has no snapshot: rule=${rule.name}")
            report(context, rule, false, context.getString(R.string.voice_dispatch_timed_not_configured), sink)
            return
        }
        if (!bridge.exists(rule.payload)) {
            Log.w(TAG, "script not found: ${rule.payload}")
            report(context, rule, false, context.getString(R.string.voice_dispatch_script_missing), sink)
            return
        }
        val existing = bridge.findTimedTaskByIdentity(rule.payload, snapshot.millis, snapshot.timeFlag)
        if (existing != null &&
            Prefs(context).voiceDispatchDuplicatePolicy == Prefs.VoiceDuplicatePolicy.REFUSE
        ) {
            Log.i(TAG, "timed refused: identical task exists id=${existing.taskId}")
            report(context, rule, false, context.getString(R.string.voice_dispatch_timed_already_set), sink)
            return
        }
        val newId = bridge.createTimedTask(rule.payload, snapshot.millis, snapshot.timeFlag, snapshot.delayMs)
        if (newId <= 0L) {
            report(context, rule, false, context.getString(R.string.voice_dispatch_timed_failed), sink)
            return
        }
        Log.i(TAG, "timed created: id=$newId path=${rule.payload}")
        // 绑定自愈：重建任务产生新 id，写回规则
        try {
            bindTimedTask?.invoke(rule.id, newId)
        } catch (t: Throwable) {
            Log.w(TAG, "bind timed task failed", t)
        }
        report(context, rule, true, context.getString(R.string.voice_dispatch_timed_set), sink)
    }
}
