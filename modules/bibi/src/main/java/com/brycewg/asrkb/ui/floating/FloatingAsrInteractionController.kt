package com.brycewg.asrkb.ui.floating

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.R
import com.brycewg.asrkb.analytics.AnalyticsManager
import com.brycewg.asrkb.asr.AsrErrorMessageMapper
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager
import com.brycewg.asrkb.tts.TtsPlaybackCoordinator
import com.brycewg.asrkb.ui.AsrVendorUi
import com.brycewg.asrkb.ui.SettingsActivity
import com.brycewg.asrkb.ui.floatingball.AsrSessionManager
import com.brycewg.asrkb.ui.floatingball.FloatingBallHoldAccessibilityPromptTracker
import com.brycewg.asrkb.ui.floatingball.FloatingBallHoldPressAction
import com.brycewg.asrkb.ui.floatingball.FloatingBallHoldRecordingTracker
import com.brycewg.asrkb.ui.floatingball.FloatingBallRecordingTapAction
import com.brycewg.asrkb.ui.floatingball.FloatingBallInteractionMode
import com.brycewg.asrkb.ui.floatingball.FloatingBallState
import com.brycewg.asrkb.ui.floatingball.FloatingBallStateMachine
import com.brycewg.asrkb.ui.floatingball.FloatingBallTouchHandler
import com.brycewg.asrkb.ui.floatingball.FloatingBallViewManager
import com.brycewg.asrkb.ui.floatingball.FloatingMenuHelper
import com.brycewg.asrkb.ui.floatingball.FloatingPanelItemId
import com.brycewg.asrkb.ui.floatingball.ResultDisplayMode
import com.brycewg.asrkb.ui.floatingball.resolveFloatingBallHoldPressAction
import com.brycewg.asrkb.ui.floatingball.resolveFloatingBallRecordingTapAction
import com.brycewg.asrkb.util.HapticFeedbackHelper
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal class FloatingAsrInteractionController(
    private val context: Context,
    private val prefs: Prefs,
    private val viewManager: FloatingBallViewManager,
    private val menuController: FloatingMenuController,
    private val stateMachine: FloatingBallStateMachine,
    private val notifier: UserNotifier,
    private val scope: CoroutineScope,
    private val tag: String,
    private val isImeVisible: () -> Boolean,
    private val startRecordingForeground: () -> Boolean,
    private val stopRecordingForeground: () -> Unit
) : AsrSessionManager.AsrSessionListener,
    FloatingBallTouchHandler.TouchEventListener {
    private enum class RecordingStartFromBallResult {
        Started,
        Failed
    }

    companion object {
        private const val SESSION_END_DECISION_DELAY_MS = 100L
        private const val AUTO_RESTART_DELAY_MS = 100L
        private const val AUTO_RETRY_DELAY_MS = 300L
        private const val ERROR_DISPLAY_MS = 1000L
        private const val EDGE_HANDLE_AUTO_HIDE_DELAY_MS = 2500L
        // ~15Hz: 驱动球图标 alpha 脉动足够细腻; 32ms(~30Hz) 时主线程消息翻倍无感知收益
        private const val AMPLITUDE_DISPATCH_INTERVAL_MS = 66L
        private const val SHAKE_START_TONE_MS = 200
        private const val SHAKE_STOP_TONE_MS = 260
    }

    lateinit var asrSessionManager: AsrSessionManager
    lateinit var applyVisibility: (String) -> Unit

    /** 监听面板（由 FloatingAsrService 注入）；进入/退出 LISTENING 时显隐。 */
    var listeningPanel: ListeningPanelHelper? = null

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var touchActiveGuard: Boolean = false

    /** 交互模式层：ROUND 圆球 → READY_PILL「发起语音」→ LISTENING_PILL「正在听...」。 */
    private var interactionMode: FloatingBallInteractionMode = FloatingBallInteractionMode.ROUND

    /** 会话结束后的延迟任务（决策/驻留迁移/错误展示/自动续听），同一时刻仅一个。 */
    private var postResultRunnable: Runnable? = null

    /** runWhenIdle 返回的取消句柄：与 postResultRunnable 同生命周期，防止滞留 TTS 单例 */
    private var pendingIdleCancel: (() -> Unit)? = null

    /** 本轮会话分发结论：null=无结论（未分发或冷却期静默）。 */
    private var lastDispatchHit: Boolean? = null

    /** 本轮会话是否产生了文字结果（commit）。 */
    private var lastSessionHadText: Boolean = false

    /** 用户主动停止后置位：本轮结束后不自动续听；下次启动识别时复位。 */
    private var suppressAutoContinue: Boolean = false

    /** 「正在听」播报等待令牌：非空 = 已发起、正播报、尚未开麦；任何实际开麦都会使其失效。 */
    private var listeningAnnounceToken: Any? = null

    /** 「发起语音」无操作收缩计时（默认 15 秒，见界面设置）。 */
    private var readyCollapseRunnable: Runnable? = null

    private fun transitionInteractionMode(mode: FloatingBallInteractionMode) {
        if (interactionMode == mode) return
        interactionMode = mode
        try {
            viewManager.setBallVisualMode(mode)
        } catch (e: Throwable) {
            Log.w(tag, "Failed to transition interaction mode to $mode", e)
        }
        val panel = listeningPanel
        if (mode == FloatingBallInteractionMode.LISTENING_PILL) {
            if (currentResultDisplayMode() == ResultDisplayMode.CAPSULE) {
                // 「仅悬浮球」形态：不弹底部面板，胶囊只显示状态字（正在听…/分发结果）
                panel?.hide()
            } else {
                panel?.show()
            }
            cancelReadyCollapseTimer()
        } else {
            panel?.hide()
            if (mode == FloatingBallInteractionMode.READY_PILL) {
                scheduleReadyCollapseTimer()
            } else {
                cancelReadyCollapseTimer()
            }
        }
    }

    /** 当前识别 UI 形态；读取失败回退面板模式（原版行为）。 */
    private fun currentResultDisplayMode(): ResultDisplayMode = try {
        prefs.floatingResultDisplayMode
    } catch (e: Throwable) {
        Log.w(tag, "Failed to read floating result display mode", e)
        ResultDisplayMode.PANEL
    }

    /** 「发起语音」停留超时后收缩回完整圆球；菜单/拖动等前台交互期间顺延。 */
    private fun scheduleReadyCollapseTimer() {
        cancelReadyCollapseTimer()
        val runnable = Runnable {
            if (interactionMode != FloatingBallInteractionMode.READY_PILL) return@Runnable
            if (isForceVisibleActive()) {
                // 菜单/移动中：稍后再查
                scheduleReadyCollapseTimer()
                return@Runnable
            }
            Log.d(tag, "Ready pill idle timeout; collapsing to round ball")
            transitionInteractionMode(FloatingBallInteractionMode.ROUND)
        }
        readyCollapseRunnable = runnable
        try {
            val seconds = try {
                prefs.voiceReadyCollapseSeconds
            } catch (e: Throwable) {
                15
            }
            handler.postDelayed(runnable, seconds * 1000L)
        } catch (e: Throwable) {
            readyCollapseRunnable = null
            Log.w(tag, "Failed to schedule ready collapse", e)
        }
    }

    private fun cancelReadyCollapseTimer() {
        readyCollapseRunnable?.let { handler.removeCallbacks(it) }
        readyCollapseRunnable = null
    }

    /**
     * 唤醒词命中：跳过 READY，直接进入 LISTENING 并开始识别（等价触发悬浮球单击后的聆听）。
     * TTS 开时同样先播「正在听」再开麦（播完约 1s 才开始收音，唤醒后需稍候再说话）。
     */
    fun onWakeTriggered() {
        if (stateMachine.isRecording || stateMachine.isProcessing) return
        startRecordingForUser()
    }

    private fun getDispatcher(): com.brycewg.asrkb.host.VoiceCommandDispatcher? = try {
        com.brycewg.asrkb.host.VoiceCommandDispatcher.getInstance(context)
    } catch (e: Throwable) {
        Log.w(tag, "Failed to get voice dispatcher", e)
        null
    }

    private fun wireDispatcherFeedback() {
        val dispatcher = getDispatcher() ?: return
        dispatcher.onHit = { _, recognizedText, _ ->
            // 结论同步记录（分发在主线程执行），驻留评估可立即读取
            lastDispatchHit = true
            handler.post {
                // 面板与 TTS 用完整文案（含识别文本）；胶囊只显示截取后的状态字
                val message = context.getString(
                    R.string.voice_dispatch_feedback_executing,
                    recognizedText
                )
                if (currentResultDisplayMode() == ResultDisplayMode.CAPSULE) {
                    viewManager.setPillContent(
                        context.getString(R.string.floating_pill_dispatch_hit)
                    )
                } else {
                    listeningPanel?.showFeedback(message)
                }
                // 命中即播；播报期间由开麦闸口推迟自动续听
                if (isTtsEnabled()) TtsPlaybackCoordinator.speak(context, message)
            }
        }
        dispatcher.onMiss = { recognizedText ->
            lastDispatchHit = false
            handler.post {
                // 面板与 TTS 用完整文案（含识别文本）；胶囊只显示截取后的状态字
                val message = context.getString(
                    R.string.voice_dispatch_feedback_no_task,
                    recognizedText
                )
                if (currentResultDisplayMode() == ResultDisplayMode.CAPSULE) {
                    viewManager.setPillContent(
                        context.getString(R.string.floating_pill_dispatch_miss)
                    )
                } else {
                    listeningPanel?.showFeedback(message)
                }
                if (isTtsEnabled()) TtsPlaybackCoordinator.speak(context, message)
            }
        }
        // v2 执行面结果：成功静默（与运行按钮一致）；失败 toast + 面板/胶囊提示
        dispatcher.onExecutionResult = { _, ok, message ->
            if (!ok) {
                handler.post {
                    val text = message ?: context.getString(R.string.voice_dispatch_script_failed)
                    showToast(text)
                    if (currentResultDisplayMode() == ResultDisplayMode.CAPSULE) {
                        viewManager.setPillContent(
                            context.getString(R.string.floating_pill_dispatch_fail)
                        )
                    } else {
                        listeningPanel?.showFeedback(text)
                    }
                }
            }
        }
    }

    /** TTS 是否开启（总开关）；关闭时所有播报与闸口延迟都不生效 */
    private fun isTtsEnabled(): Boolean = try {
        prefs.ttsEnabled
    } catch (e: Throwable) {
        false
    }

    /**
     * 识别完成即分发（识别/判停流程保持 bibi 模块原生行为，分发只是结果挂钩）。
     */
    private fun dispatchRecognizedText(text: String) {
        if (text.isBlank()) return
        wireDispatcherFeedback()
        val dispatcher = getDispatcher() ?: return
        dispatcher.maybeDispatch(text)
    }

    private fun readDispatchContinueMode(): Prefs.DispatchContinueMode = try {
        prefs.dispatchContinueMode
    } catch (e: Throwable) {
        Log.w(tag, "Failed to read dispatch continue mode", e)
        Prefs.DispatchContinueMode.NONE
    }

    private fun dispatchFeedbackHoldMs(): Long = try {
        prefs.dispatchFeedbackHoldSeconds.coerceIn(0, 10) * 1000L
    } catch (e: Throwable) {
        2000L
    }

    private fun cancelPostResultRunnable() {
        postResultRunnable?.let { handler.removeCallbacks(it) }
        postResultRunnable = null
        pendingIdleCancel?.invoke()
        pendingIdleCancel = null
    }

    /**
     * 会话结束（Idle）后的决策：稍候一拍让 commit/分发结论落定，再分流——
     * 空结果（②③模式且非用户主动停止）保持监听态直接重录；
     * 其余走「驻留展示反馈 → READY → 按分发结论续听」。
     */
    private fun scheduleSessionEndDecision() {
        cancelPostResultRunnable()
        val runnable = Runnable {
            postResultRunnable = null
            if (interactionMode != FloatingBallInteractionMode.LISTENING_PILL ||
                stateMachine.isRecording ||
                stateMachine.isProcessing
            ) {
                return@Runnable
            }
            if (!lastSessionHadText &&
                !suppressAutoContinue &&
                readDispatchContinueMode() != Prefs.DispatchContinueMode.NONE
            ) {
                scheduleAutoRestart()
            } else {
                scheduleReadyTransitionAfterHold()
            }
        }
        postResultRunnable = runnable
        handler.postDelayed(runnable, SESSION_END_DECISION_DELAY_MS)
    }

    /** 驻留展示分发反馈（时长可配，0=不驻留）后回「发起语音」，并按本轮分发结论决定是否续听。 */
    private fun scheduleReadyTransitionAfterHold() {
        cancelPostResultRunnable()
        val holdMs = dispatchFeedbackHoldMs()
        val runnable = Runnable {
            postResultRunnable = null
            if (interactionMode != FloatingBallInteractionMode.LISTENING_PILL ||
                stateMachine.isRecording ||
                stateMachine.isProcessing
            ) {
                return@Runnable
            }
            if (isTtsEnabled() && TtsPlaybackCoordinator.isBusy) {
                // TTS 播报未结束：保持监听面板展示反馈，播完再回 READY 并续听
                val wait = Runnable { /* 播报等待占位（runWhenIdle 内校验 token） */ }
                postResultRunnable = wait
                pendingIdleCancel = TtsPlaybackCoordinator.runWhenIdle {
                    handler.post {
                        if (postResultRunnable !== wait) return@post
                        postResultRunnable = null
                        transitionToReadyAndMaybeRestart()
                    }
                }
                return@Runnable
            }
            transitionToReadyAndMaybeRestart()
        }
        postResultRunnable = runnable
        if (holdMs > 0L) {
            handler.postDelayed(runnable, holdMs)
        } else {
            runnable.run()
        }
    }

    private fun transitionToReadyAndMaybeRestart() {
        if (interactionMode != FloatingBallInteractionMode.LISTENING_PILL ||
            stateMachine.isRecording ||
            stateMachine.isProcessing
        ) {
            return
        }
        transitionInteractionMode(FloatingBallInteractionMode.READY_PILL)
        maybeScheduleAutoRestartAfterDispatch()
    }

    private fun maybeScheduleAutoRestartAfterDispatch() {
        if (suppressAutoContinue) return
        val hit = lastDispatchHit
        // 冷却期静默轮（hit == null，无"已分发/未命中"提示）按用户决策继续循环，不再中断
        val shouldContinue = when (readDispatchContinueMode()) {
            Prefs.DispatchContinueMode.ALWAYS -> true
            Prefs.DispatchContinueMode.ON_MISS -> hit != true
            Prefs.DispatchContinueMode.NONE -> false
        }
        if (shouldContinue) {
            scheduleAutoRestart()
        }
    }

    /**
     * 自动续听：READY 闪现后重开识别（正常轮次）；空结果续听则保持 LISTENING 直接重录。
     * 启动失败（麦克风释放慢等瞬时原因）自动重试一次；重试仍失败退回「发起语音」。
     *
     * TTS 开启且仍在播报时，重开麦推迟到播报结束（决策 §4：播放声音时不开麦监听）；
     * TTS 关闭时行为与原版完全一致（100ms 后重开）。
     */
    private fun scheduleAutoRestart(isRetry: Boolean = false) {
        cancelPostResultRunnable()
        val runnable = Runnable {
            postResultRunnable = null
            if (stateMachine.isRecording || stateMachine.isProcessing) return@Runnable
            val fromListening = interactionMode == FloatingBallInteractionMode.LISTENING_PILL
            val fromReady = interactionMode == FloatingBallInteractionMode.READY_PILL
            if (!fromListening && !fromReady) return@Runnable
            if (isTtsEnabled() && TtsPlaybackCoordinator.isBusy) {
                val wait = Runnable { /* 播报等待占位（runWhenIdle 内校验 token） */ }
                postResultRunnable = wait
                pendingIdleCancel = TtsPlaybackCoordinator.runWhenIdle {
                    handler.post {
                        if (postResultRunnable !== wait) return@post
                        postResultRunnable = null
                        startAutoRestart(fromListening, fromReady, isRetry)
                    }
                }
                return@Runnable
            }
            startAutoRestart(fromListening, fromReady, isRetry)
        }
        postResultRunnable = runnable
        handler.postDelayed(runnable, if (isRetry) AUTO_RETRY_DELAY_MS else AUTO_RESTART_DELAY_MS)
    }

    private fun startAutoRestart(fromListening: Boolean, fromReady: Boolean, isRetry: Boolean) {
        if (stateMachine.isRecording || stateMachine.isProcessing) return
        if (startRecording()) {
            if (fromReady) {
                transitionInteractionMode(FloatingBallInteractionMode.LISTENING_PILL)
            }
        } else if (!isRetry) {
            scheduleAutoRestart(isRetry = true)
        } else if (fromListening) {
            transitionInteractionMode(FloatingBallInteractionMode.READY_PILL)
        }
    }

    /** 出错续听（②③模式，无次数保护）：面板展示错误信息片刻后收起并重新聆听。 */
    private fun scheduleErrorRestart() {
        cancelPostResultRunnable()
        val runnable = Runnable {
            postResultRunnable = null
            if (interactionMode != FloatingBallInteractionMode.LISTENING_PILL ||
                stateMachine.isRecording ||
                stateMachine.isProcessing
            ) {
                return@Runnable
            }
            transitionInteractionMode(FloatingBallInteractionMode.READY_PILL)
            if (!suppressAutoContinue) {
                scheduleAutoRestart()
            }
        }
        postResultRunnable = runnable
        handler.postDelayed(runnable, ERROR_DISPLAY_MS)
    }

    /** 监听面板停止按钮：与单击「正在听...」胶囊等价。 */
    fun onListeningPanelStopClicked() {
        if (stateMachine.isRecording) {
            stopRecording()
        } else if (stateMachine.isProcessing) {
            cancelCurrentSession()
        } else {
            // 「正在听」播报等待中点停止：取消播报，不进入录音
            listeningAnnounceToken = null
            suppressAutoContinue = true
            cancelPostResultRunnable()
            // 打断进行中的分发反馈播报（幂等；TTS 关时为空操作）
            TtsPlaybackCoordinator.stopSpeaking()
            transitionInteractionMode(FloatingBallInteractionMode.READY_PILL)
        }
    }

    private var postErrorResetStateRunnable: Runnable? = null
    private var volumeKeySessionActive: Boolean = false
    private var shakeSessionActive: Boolean = false
    private var shakeFeedbackReleaseRunnable: Runnable? = null
    private var shakeFeedbackStartRecordingRunnable: Runnable? = null
    private var shakeFeedbackTrack: android.media.AudioTrack? = null
    private val holdRecordingTracker = FloatingBallHoldRecordingTracker()
    private val holdAccessibilityPromptTracker = FloatingBallHoldAccessibilityPromptTracker()
    private var pendingAmplitude: Float? = null
    private var amplitudeDispatchRunnable: Runnable? = null
    private var lastAmplitudeDispatchUptimeMs: Long = 0L

    fun isForceVisibleActive(): Boolean = menuController.isForceVisibleMenuActive() ||
        stateMachine.isMoveMode ||
        touchActiveGuard

    fun cleanup() {
        cancelReadyCollapseTimer()
        cancelPostResultRunnable()
        cancelPostErrorResetState()
        cancelAmplitudeDispatch()
        cancelShakeFeedbackStartRecording()
        cancelShakeFeedbackTone()
        listeningAnnounceToken = null
        TtsPlaybackCoordinator.stopSpeaking()
        stopRecordingForeground()
        // 回调槽挂在进程级单例上，服务销毁后不清空会滞留整个 Service 图
        getDispatcher()?.let { d ->
            d.onHit = null
            d.onMiss = null
            d.onExecutionResult = null
        }
        try {
            menuController.hideAll()
        } catch (e: Throwable) {
            Log.w(tag, "Failed to hide menus in cleanup", e)
        }
    }

    private fun updateVisibilityByPref(src: String = "update_visibility") {
        if (!this::applyVisibility.isInitialized) return
        applyVisibility(src)
    }

    // ==================== 录音控制 ====================

    /** Script-triggered start (via $bibi.start() JS API); silent, no volume-key toast. */
    fun onScriptStart() {
        if (stateMachine.isRecording || stateMachine.isProcessing) return
        startRecording()
    }

    /** Script-triggered stop (via $bibi.stop() JS API). */
    fun onScriptStop() {
        if (!stateMachine.isRecording) return
        stopRecording()
    }

    fun onVolumeKeyStart() {
        if (stateMachine.isRecording || stateMachine.isProcessing) return
        if (startRecordingForUser(fromVolumeKey = true)) showVolumeKeyStatusToast(R.string.toast_volume_key_recording_started)
    }

    fun onVolumeKeyStop() {
        if (!stateMachine.isRecording) return
        stopRecording()
        showVolumeKeyStatusToast(R.string.toast_volume_key_recording_stopped)
    }

    fun onVolumeKeyToggle() {
        if (stateMachine.isRecording) {
            stopRecording()
            showVolumeKeyStatusToast(R.string.toast_volume_key_recording_stopped)
            return
        }
        if (stateMachine.isProcessing) return
        if (startRecordingForUser(fromVolumeKey = true)) showVolumeKeyStatusToast(R.string.toast_volume_key_recording_started)
    }

    fun onShakeRecordingToggle() {
        if (stateMachine.isRecording) {
            stopRecording()
            // 先停麦再播停止音，避免录音释放与提示音抢焦点截断。
            playShakeRecordingFeedback(starting = false)
            return
        }
        if (stateMachine.isProcessing) return
        if (isTtsEnabled()) {
            // 总开关开：以「正在听」播报替代开始提示音（同样先播后开麦）
            startRecordingForUser(fromShake = true)
            return
        }
        // 先播开始音，再开麦，避免 AudioRecord 抢焦点截断提示音。
        playShakeRecordingFeedback(starting = true) {
            if (!stateMachine.isRecording && !stateMachine.isProcessing) {
                startRecording(fromShake = true)
            }
        }
    }

    private fun startRecording(
        fromVolumeKey: Boolean = false,
        fromShake: Boolean = false
    ): Boolean {
        Log.d(tag, "startRecording called")

        // 抢话优先：开麦前打断任何进行中的 TTS 播报（幂等，空闲时为 no-op）
        TtsPlaybackCoordinator.stopSpeaking()
        // 任何实际开麦都使「正在听」播报等待回调失效，避免双重启动
        listeningAnnounceToken = null

        // 新一轮识别：清掉会话结束决策/续听待办，重置本轮分发结论；用户启动恢复续听资格
        cancelPostResultRunnable()
        lastDispatchHit = null
        lastSessionHadText = false
        suppressAutoContinue = false
        if (!canStartRecording()) return false

        if (!startRecordingForeground()) {
            Log.w(tag, "Failed to enter microphone foreground state")
            logRecordingStartPath(
                event = "microphone_fgs_start_failed",
                fromVolumeKey = fromVolumeKey,
                fromShake = fromShake,
                success = false
            )
            showToast(context.getString(R.string.toast_floating_recording_foreground_failed))
            return false
        }

        logRecordingStartPath(
            event = "microphone_fgs_started",
            fromVolumeKey = fromVolumeKey,
            fromShake = fromShake,
            success = true
        )

        return startAsrRecording(fromVolumeKey = fromVolumeKey, fromShake = fromShake)
    }

    /**
     * 用户主动发起语音（点击/音量键/摇一摇）：总开关开时先播「正在听」再开麦——
     * 麦克风与扬声器不能同时工作，否则 TTS 声音会被 ASR 收进识别流；
     * 关闭时直通原 startRecording，行为与引入 TTS 前完全一致。
     *
     * @return true 表示已开始录音或已进入播报等待
     */
    private fun startRecordingForUser(
        fromVolumeKey: Boolean = false,
        fromShake: Boolean = false
    ): Boolean {
        if (!isTtsEnabled()) {
            val started = startRecording(fromVolumeKey, fromShake)
            if (started) {
                transitionInteractionMode(FloatingBallInteractionMode.LISTENING_PILL)
            }
            return started
        }
        if (!canStartRecording()) return false
        // 提前清掉上一轮遗留待办，防止驻留/续听 runnable 在播报窗口把面板拉回 READY
        cancelPostResultRunnable()
        suppressAutoContinue = false
        val token = Any()
        listeningAnnounceToken = token
        transitionInteractionMode(FloatingBallInteractionMode.LISTENING_PILL)
        TtsPlaybackCoordinator.speak(
            context,
            context.getString(R.string.tts_listening_announcement)
        ) {
            // 期间再次发起会使令牌失效（由 startRecording 统一失效），旧回调不再开麦
            if (listeningAnnounceToken !== token) return@speak
            listeningAnnounceToken = null
            if (stateMachine.isRecording || stateMachine.isProcessing) return@speak
            if (!startRecording(fromVolumeKey, fromShake)) {
                transitionInteractionMode(FloatingBallInteractionMode.READY_PILL)
            }
        }
        return true
    }

    private fun canStartRecording(): Boolean {
        if (!hasRecordAudioPermission()) {
            Log.w(tag, "No record audio permission")
            showToast(context.getString(R.string.asr_error_mic_permission_denied))
            return false
        }

        if (!prefs.hasAsrKeys()) {
            Log.w(tag, "No ASR keys configured")
            showToast(context.getString(R.string.hint_need_keys))
            return false
        }

        return true
    }

    private fun startAsrRecording(fromVolumeKey: Boolean, fromShake: Boolean): Boolean {
        // 开始录音前切换为激活态图标
        try {
            viewManager.getBallView()?.findViewById<android.widget.ImageView>(R.id.ballIcon)
                ?.setImageResource(R.drawable.microphone_floatingball)
        } catch (e: Throwable) {
            Log.w(tag, "Failed to reset icon to mic", e)
        }

        volumeKeySessionActive = fromVolumeKey
        shakeSessionActive = fromShake
        logRecordingStartPath(
            event = "asr_start_requested",
            fromVolumeKey = fromVolumeKey,
            fromShake = fromShake,
            success = true
        )
        try {
            asrSessionManager.startRecording()
        } catch (t: Throwable) {
            Log.e(tag, "Failed to start ASR recording", t)
            volumeKeySessionActive = false
            shakeSessionActive = false
            stopRecordingForeground()
            showToast(
                context.getString(
                    R.string.floating_asr_error,
                    t.message ?: t.javaClass.simpleName
                )
            )
            return false
        }
        updateVisibilityByPref("start_recording")
        return true
    }

    private fun logRecordingStartPath(
        event: String,
        fromVolumeKey: Boolean,
        fromShake: Boolean,
        success: Boolean
    ) {
        val data = mapOf(
            "method" to "service_microphone_fgs",
            "fromVolumeKey" to fromVolumeKey,
            "fromShake" to fromShake,
            "success" to success
        )
        Log.i(
            tag,
            "recording_start_path event=$event method=service_microphone_fgs " +
                "fromVolumeKey=$fromVolumeKey fromShake=$fromShake success=$success"
        )
        DebugLogManager.logPersistent(context, "float", event, data)
    }

    private fun stopRecording() {
        Log.d(tag, "stopRecording called")
        // 用户主动停止：本轮结束后不自动续听
        suppressAutoContinue = true
        volumeKeySessionActive = false
        shakeSessionActive = false
        asrSessionManager.stopRecording()
        stopRecordingForeground()
        updateVisibilityByPref("stop_recording")
    }

    private fun playShakeRecordingFeedback(
        starting: Boolean,
        onToneFinished: (() -> Unit)? = null
    ) {
        if (!prefs.shakeRecordingSoundEnabled) {
            onToneFinished?.invoke()
            return
        }
        val durationMs = if (starting) SHAKE_START_TONE_MS else SHAKE_STOP_TONE_MS
        // USAGE_MEDIA 已由系统按 STREAM_MUSIC 缩放；媒体为 0 时跳过播放。
        // 不再在 PCM / setVolume 上二次乘媒体比例，避免低音量平方衰减到听不见。
        val mediaAudible = isMediaStreamAudible()
        if (mediaAudible) {
            if (!starting) {
                // 停麦后若仍残留通话模式，USAGE_MEDIA 会被压到接近无声。
                ensureNormalAudioModeForMediaTone()
            }
            playShakeRecordingTone(starting = starting, durationMs = durationMs)
        }
        try {
            if (starting) {
                HapticFeedbackHelper.vibratePulses(
                    context = context,
                    count = 1,
                    pulseMs = 30L,
                    gapMs = 0L,
                    amplitude = 55
                )
            } else {
                HapticFeedbackHelper.vibratePulses(
                    context = context,
                    count = 2,
                    pulseMs = 80L,
                    gapMs = 140L,
                    amplitude = 220
                )
            }
        } catch (t: Throwable) {
            Log.w(tag, "Failed to vibrate shake recording feedback", t)
        }
        if (DebugLogManager.isRecording()) {
            DebugLogManager.log(
                "float",
                "shake_feedback",
                mapOf(
                    "phase" to if (starting) "start" else "stop",
                    "pulses" to if (starting) 1 else 2,
                    "toneMs" to durationMs,
                    "mediaAudible" to mediaAudible
                )
            )
        }
        if (onToneFinished != null) {
            cancelShakeFeedbackStartRecording()
            if (!mediaAudible) {
                onToneFinished()
            } else {
                val r = Runnable {
                    shakeFeedbackStartRecordingRunnable = null
                    onToneFinished()
                }
                shakeFeedbackStartRecordingRunnable = r
                handler.postDelayed(r, durationMs + 20L)
            }
        }
    }

    private fun isMediaStreamAudible(): Boolean {
        return try {
            val am = context.getSystemService(android.media.AudioManager::class.java) ?: return true
            am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) > 0
        } catch (t: Throwable) {
            Log.w(tag, "Failed to read media stream volume", t)
            true
        }
    }

    private fun ensureNormalAudioModeForMediaTone() {
        try {
            val am = context.getSystemService(android.media.AudioManager::class.java) ?: return
            val mode = am.mode
            if (mode == android.media.AudioManager.MODE_IN_COMMUNICATION ||
                mode == android.media.AudioManager.MODE_IN_CALL
            ) {
                am.mode = android.media.AudioManager.MODE_NORMAL
                if (DebugLogManager.isRecording()) {
                    DebugLogManager.log(
                        "float",
                        "shake_tone_mode_reset",
                        mapOf("fromMode" to mode)
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(tag, "Failed to reset audio mode before stop tone", t)
        }
    }

    private fun playShakeRecordingTone(starting: Boolean, durationMs: Int) {
        cancelShakeFeedbackTone()
        try {
            // USAGE_MEDIA → 跟随 STREAM_MUSIC。PCM 用固定峰值，由系统音量缩放。
            // 开始：单音 880Hz；停止：990Hz→660Hz 下降双音（听感与开始区分，手机喇叭更易放出来）。
            val sampleRate = 16_000
            val numSamples = (sampleRate * durationMs / 1000).coerceAtLeast(1)
            val buffer = ShortArray(numSamples)
            val fadeSamples = (sampleRate / 100).coerceAtLeast(1)
            val peak = 0.35
            val split = if (starting) numSamples else (numSamples * 1 / 2).coerceAtLeast(1)
            for (i in 0 until numSamples) {
                val frequencyHz = when {
                    starting -> 880.0
                    i < split -> 990.0
                    else -> 660.0
                }
                val t = i.toDouble() / sampleRate
                val env = when {
                    i < fadeSamples -> i.toDouble() / fadeSamples
                    i > numSamples - fadeSamples -> (numSamples - i).toDouble() / fadeSamples
                    !starting && i >= split && i < split + fadeSamples ->
                        (i - split).toDouble() / fadeSamples
                    else -> 1.0
                }
                buffer[i] = (sin(2.0 * Math.PI * frequencyHz * t) * peak * env * Short.MAX_VALUE)
                    .toInt()
                    .toShort()
            }
            val attrs = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val format = android.media.AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val track = android.media.AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(
                    maxOf(
                        buffer.size * 2,
                        android.media.AudioTrack.getMinBufferSize(
                            sampleRate,
                            android.media.AudioFormat.CHANNEL_OUT_MONO,
                            android.media.AudioFormat.ENCODING_PCM_16BIT
                        )
                    )
                )
                .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                .build()
            val written = track.write(buffer, 0, buffer.size)
            if (written < 0) {
                track.release()
                throw IllegalStateException("AudioTrack write failed: $written")
            }
            shakeFeedbackTrack = track
            track.play()
            val release = Runnable {
                shakeFeedbackReleaseRunnable = null
                releaseShakeFeedbackTrack()
            }
            shakeFeedbackReleaseRunnable = release
            handler.postDelayed(release, durationMs + 40L)
        } catch (t: Throwable) {
            Log.w(tag, "Failed to play shake recording tone", t)
            releaseShakeFeedbackTrack()
        }
    }

    private fun cancelShakeFeedbackTone() {
        shakeFeedbackReleaseRunnable?.let { handler.removeCallbacks(it) }
        shakeFeedbackReleaseRunnable = null
        releaseShakeFeedbackTrack()
    }

    private fun cancelShakeFeedbackStartRecording() {
        shakeFeedbackStartRecordingRunnable?.let { handler.removeCallbacks(it) }
        shakeFeedbackStartRecordingRunnable = null
    }

    private fun releaseShakeFeedbackTrack() {
        val track = shakeFeedbackTrack ?: return
        shakeFeedbackTrack = null
        try {
            track.stop()
        } catch (_: Throwable) {
        }
        try {
            track.release()
        } catch (t: Throwable) {
            Log.w(tag, "Failed to release shake recording tone", t)
        }
    }

    private fun cancelCurrentSession() {
        Log.d(tag, "cancelCurrentSession called")
        // 用户主动取消：本轮结束后不自动续听
        suppressAutoContinue = true
        cancelShakeFeedbackStartRecording()
        volumeKeySessionActive = false
        shakeSessionActive = false
        asrSessionManager.cancelSession()
        stopRecordingForeground()
        updateVisibilityByPref("cancel_session")
    }

    // ==================== 延迟任务 ====================

    private fun cancelDelayedRunnable(runnable: Runnable?, label: String): Runnable? {
        val r = runnable ?: return null
        try {
            handler.removeCallbacks(r)
        } catch (e: Throwable) {
            Log.w(tag, "Failed to cancel $label runnable", e)
        }
        return null
    }

    private fun cancelPostErrorResetState() {
        postErrorResetStateRunnable =
            cancelDelayedRunnable(postErrorResetStateRunnable, "post-error reset state")
    }

    private fun cancelAmplitudeDispatch() {
        amplitudeDispatchRunnable =
            cancelDelayedRunnable(amplitudeDispatchRunnable, "amplitude dispatch")
        pendingAmplitude = null
    }

    private fun enqueueAmplitude(amplitude: Float) {
        pendingAmplitude = amplitude.coerceIn(0f, 1f)
        if (amplitudeDispatchRunnable != null) return

        val now = SystemClock.uptimeMillis()
        val elapsed = now - lastAmplitudeDispatchUptimeMs
        val delay = (AMPLITUDE_DISPATCH_INTERVAL_MS - elapsed).coerceAtLeast(0L)
        val runnable = Runnable {
            amplitudeDispatchRunnable = null
            lastAmplitudeDispatchUptimeMs = SystemClock.uptimeMillis()
            val nextAmplitude = pendingAmplitude ?: return@Runnable
            pendingAmplitude = null
            if (stateMachine.isRecording) {
                viewManager.updateAmplitude(nextAmplitude)
            }
        }
        amplitudeDispatchRunnable = runnable
        try {
            handler.postDelayed(runnable, delay)
        } catch (e: Throwable) {
            amplitudeDispatchRunnable = null
            Log.w(tag, "Failed to schedule amplitude dispatch", e)
        }
    }

    private fun schedulePostErrorResetState() {
        cancelPostErrorResetState()
        val runnable = Runnable {
            if (!stateMachine.isError) return@Runnable
            try {
                stateMachine.transitionTo(FloatingBallState.Idle)
                viewManager.updateStateVisual(FloatingBallState.Idle)
            } catch (e: Throwable) {
                Log.w(tag, "Failed to reset state to Idle after error animation", e)
            }
            updateVisibilityByPref("post_error_reset")
        }
        postErrorResetStateRunnable = runnable
        try {
            handler.postDelayed(runnable, 1500L)
        } catch (e: Throwable) {
            Log.w(tag, "Failed to schedule state reset after error", e)
        }
    }

    // ==================== AsrSessionManager.AsrSessionListener ====================

    override fun onSessionStateChanged(state: FloatingBallState) {
        stateMachine.transitionTo(state)
        val recordingActive = isRecordingCaptureActive()
        if (state !is FloatingBallState.Recording && !recordingActive) {
            cancelAmplitudeDispatch()
            stopRecordingForeground()
        }
        handler.post {
            viewManager.updateStateVisual(state)
            // 旁路启动（音量键/摇一摇/JS）时同步交互模式为 LISTENING；
            // 识别完成驻留片刻（展示分发反馈）回 READY，出错直接回 READY。
            when (state) {
                is FloatingBallState.Recording, is FloatingBallState.Processing -> {
                    transitionInteractionMode(FloatingBallInteractionMode.LISTENING_PILL)
                    if (state is FloatingBallState.Recording &&
                        currentResultDisplayMode() == ResultDisplayMode.CAPSULE
                    ) {
                        // 新一轮录音开始：胶囊回到「正在听…」，覆盖上一轮遗留的反馈文字
                        // （②③模式自动续听时保持 LISTENING 不经过 READY，需在此复位）
                        viewManager.resetPillToListening()
                    }
                }

                is FloatingBallState.Idle -> {
                    if (interactionMode == FloatingBallInteractionMode.LISTENING_PILL) {
                        scheduleSessionEndDecision()
                    }
                }

                is FloatingBallState.Error -> {
                    cancelPostResultRunnable()
                    if (interactionMode == FloatingBallInteractionMode.LISTENING_PILL) {
                        if (!suppressAutoContinue &&
                            readDispatchContinueMode() != Prefs.DispatchContinueMode.NONE
                        ) {
                            // ②③模式：出错也续听（展示错误片刻后收起重录）
                            scheduleErrorRestart()
                        } else {
                            transitionInteractionMode(FloatingBallInteractionMode.READY_PILL)
                        }
                    }
                }

                else -> Unit
            }
            updateVisibilityByPref("session_state_changed")
        }
    }

    override fun onResultCommitted(text: String, success: Boolean) {
        volumeKeySessionActive = false
        shakeSessionActive = false
        if (!stateMachine.isRecording) {
            stopRecordingForeground()
        }
        handler.post {
            if (success) {
                viewManager.showCompletionTick()
            }
            if (text.isNotBlank() || success) {
                persistFloatingCommit(text)
            }
            // 识别完成即分发（总开关已移除，所有识别结果统一匹配规则）
            lastSessionHadText = text.isNotBlank()
            dispatchRecognizedText(text)
        }
    }

    private fun persistFloatingCommit(text: String) {
        try {
            val audioMs = asrSessionManager.popLastAudioMsForStats()
            val historyRecordId = asrSessionManager.popLastHistoryRecordId()
            val historyRawText = asrSessionManager.popLastHistoryRawText() ?: text
            val totalElapsedMs = asrSessionManager.popLastTotalElapsedMsForStats()
            val chars = com.brycewg.asrkb.util.TextSanitizer.countEffectiveChars(text)
            val ai = try {
                asrSessionManager.wasLastAiUsed()
            } catch (_: Throwable) {
                false
            }
            val aiPostMs = try {
                asrSessionManager.getLastAiPostMs()
            } catch (_: Throwable) {
                0L
            }
            val aiPostStatus = try {
                asrSessionManager.getLastAiPostStatus()
            } catch (_: Throwable) {
                if (ai) {
                    com.brycewg.asrkb.store.AsrHistoryStore.AiPostStatus.SUCCESS
                } else {
                    com.brycewg.asrkb.store.AsrHistoryStore.AiPostStatus.NONE
                }
            }
            val llmVendorId = try {
                asrSessionManager.getLastLlmVendorId()
            } catch (_: Throwable) {
                null
            }
            val promptSelection = try {
                asrSessionManager.getLastPromptSelection()
            } catch (_: Throwable) {
                null
            }
            val vendorForRecord = try {
                asrSessionManager.peekLastFinalVendorForStats()
            } catch (t: Throwable) {
                Log.w(tag, "Failed to get final vendor for stats", t)
                prefs.asrVendor
            }
            val timingTrace = asrSessionManager.completeLastHistoryTiming()
            val procMs = timingTrace
                ?.stageDurationMs(com.brycewg.asrkb.store.AsrHistoryTimingStage.RECOGNITION)
                ?.takeIf { it > 0L }
                ?: asrSessionManager.getLastRequestDuration()
                ?: 0L
            val disableHistory = prefs.disableAsrHistory
            val disableStats = prefs.disableUsageStats
            val retention = prefs.audioHistoryRetentionCount
            scope.launch(Dispatchers.IO) {
                try {
                    AnalyticsManager.recordAsrEvent(
                        context = context,
                        vendorId = vendorForRecord.id,
                        audioMs = audioMs,
                        procMs = procMs,
                        source = "floating",
                        aiProcessed = ai,
                        charCount = chars
                    )
                    if (!disableStats) {
                        prefs.recordUsageCommit(
                            "floating",
                            vendorForRecord,
                            audioMs,
                            chars,
                            procMs
                        )
                    }
                    if (!disableHistory) {
                        val store = com.brycewg.asrkb.store.AsrHistoryStore(context)
                        store.add(
                            com.brycewg.asrkb.store.AsrHistoryStore.AsrHistoryRecord(
                                id = historyRecordId,
                                timestamp = System.currentTimeMillis(),
                                text = text,
                                rawText = historyRawText,
                                vendorId = vendorForRecord.id,
                                audioMs = audioMs,
                                totalElapsedMs = timingTrace?.totalElapsedMs ?: totalElapsedMs,
                                procMs = procMs,
                                source = "floating",
                                aiProcessed = ai,
                                aiPostMs = aiPostMs,
                                aiPostStatus = aiPostStatus,
                                llmVendorId = llmVendorId,
                                charCount = chars,
                                timingTrace = timingTrace,
                                promptSelection = promptSelection
                            )
                        )
                        timingTrace?.let { trace ->
                            com.brycewg.asrkb.store.AsrHistoryTimingDiagnostics.logSaved(
                                "floating",
                                trace
                            )
                        }
                        com.brycewg.asrkb.store.AsrHistoryAudioStore.pruneAsync(
                            context,
                            retention
                        )
                    } else {
                        com.brycewg.asrkb.store.AsrHistoryAudioStore(context).delete(historyRecordId)
                    }
                } catch (t: Throwable) {
                    Log.e(tag, "Failed to record usage stats (floating)", t)
                }
            }
        } catch (t: Throwable) {
            Log.e(tag, "Failed to snapshot floating commit stats", t)
        }
    }

    override fun onError(message: String) {
        volumeKeySessionActive = false
        shakeSessionActive = false
        stopRecordingForeground()
        cancelAmplitudeDispatch()
        handler.post {
            val mapped = AsrErrorMessageMapper.map(context, message)
            if (mapped != null) {
                showToast(mapped)
            } else {
                showToast(context.getString(R.string.floating_asr_error, message))
            }

            schedulePostErrorResetState()
        }
    }

    override fun onAmplitude(amplitude: Float) {
        try {
            handler.post {
                enqueueAmplitude(amplitude)
            }
        } catch (e: Throwable) {
            Log.w(tag, "Failed to post amplitude update", e)
        }
    }

    // ==================== FloatingBallTouchHandler.TouchEventListener ====================

    override fun onSingleTap() {
        if (stateMachine.isMoveMode) {
            // 移动模式单击：退出移动模式，停在哪算哪
            stateMachine.transitionTo(FloatingBallState.Idle)
            viewManager.getBallView()?.let {
                viewManager.persistBallPosition()
            }
            hideRadialMenu()
            hideVendorMenu()
            return
        }

        when (interactionMode) {
            FloatingBallInteractionMode.ROUND -> {
                // 单击圆球 → 「发起语音」胶囊（不启动录音）；顺带打断残留的反馈播报
                TtsPlaybackCoordinator.stopSpeaking()
                transitionInteractionMode(FloatingBallInteractionMode.READY_PILL)
            }

            FloatingBallInteractionMode.READY_PILL -> {
                // 单击「发起语音」→ 开始录音 + 「正在听...」（TTS 开时先播「正在听」再开麦）
                if (stateMachine.isRecording || stateMachine.isProcessing) {
                    // 旁路已启动录音：仅同步视觉
                    transitionInteractionMode(FloatingBallInteractionMode.LISTENING_PILL)
                    return
                }
                startRecordingForUser()
            }

            FloatingBallInteractionMode.LISTENING_PILL -> {
                // 「正在听」播报尚未开麦：再次点击 = 跳过播报立即开麦
                if (listeningAnnounceToken != null &&
                    !stateMachine.isRecording &&
                    !stateMachine.isProcessing
                ) {
                    if (startRecordingFromBall() == RecordingStartFromBallResult.Failed) {
                        transitionInteractionMode(FloatingBallInteractionMode.READY_PILL)
                    }
                } else if (stateMachine.isRecording) {
                    // 单击「正在听...」→ 停止并回「发起语音」
                    stopRecording()
                } else if (stateMachine.isProcessing) {
                    cancelCurrentSession()
                } else {
                    listeningAnnounceToken = null
                    suppressAutoContinue = true
                    cancelPostResultRunnable()
                    // 打断进行中的分发反馈播报（幂等；TTS 关时为空操作）
                    TtsPlaybackCoordinator.stopSpeaking()
                    transitionInteractionMode(FloatingBallInteractionMode.READY_PILL)
                }
            }
        }
    }

    private fun isHoldToRecordEnabled(): Boolean = try {
        prefs.floatingBallHoldToRecordEnabled
    } catch (e: Throwable) {
        Log.w(tag, "Failed to read floating hold-to-record preference", e)
        false
    }

    override fun onLongPress() {
        if (stateMachine.isMoveMode) return
        // 交互重做：长按直接弹出设置菜单（不再按住录音）
        holdAccessibilityPromptTracker.clear()
        touchActiveGuard = true
        updateVisibilityByPref("long_press")
        val center = viewManager.getBallCenterSnapshot()
        val alpha = getMenuAlphaOrDefault()
        menuController.showRadialMenu(center, alpha, buildRadialMenuItems()) {
            touchActiveGuard = false
            updateVisibilityByPref("radial_menu_dismiss")
        }
    }

    override fun onLongPressRelease() {
        touchActiveGuard = false
        updateVisibilityByPref("long_press_release")
    }

    override fun onLongPressGestureMovedBeyondSlop() {
        // 长按后拖动不再移球/收菜单（用户决策）；仅清理按住提示状态
        holdAccessibilityPromptTracker.clear()
    }

    override fun onLongPressCancel() {
        cancelHoldRecordingForGesture()
        touchActiveGuard = false
        updateVisibilityByPref("long_press_cancel")
    }

    override fun onLongPressDragStart(initialRawX: Float, initialRawY: Float) {
        // 交互重做后不再使用拖拽展开菜单；长按后拖动由 onMoveStarted 处理
    }

    override fun onLongPressDragMove(rawX: Float, rawY: Float) {
        // no-op
    }

    override fun onLongPressDragRelease(rawX: Float, rawY: Float) {
        // no-op
    }

    override fun onMoveStarted() {
        cancelHoldRecordingForGesture()
        touchActiveGuard = true
        updateVisibilityByPref("move_started")
    }

    override fun onMoveEnded() {
        touchActiveGuard = false
        if (stateMachine.isMoveMode) {
            stateMachine.transitionTo(FloatingBallState.Idle)
            try {
                viewManager.updateStateVisual(FloatingBallState.Idle)
            } catch (e: Throwable) {
                Log.w(tag, "Failed to update state visual after move end", e)
            }
        }
        updateVisibilityByPref("move_ended")
    }

    override fun onDragCancelled() {
        menuController.dismissDragSession()
        touchActiveGuard = false
        updateVisibilityByPref("drag_cancelled")
    }

    private fun startRecordingFromBall(): RecordingStartFromBallResult {
        // 识别不再依赖无障碍（IME 已移除，结果不写输入框；历史/控制台/$bibi/面板照常）
        return if (startRecording()) {
            RecordingStartFromBallResult.Started
        } else {
            RecordingStartFromBallResult.Failed
        }
    }

    private fun cancelHoldRecordingForGesture() {
        holdAccessibilityPromptTracker.clear()
        val shouldCancel = holdRecordingTracker.consumeCancelForGesture(isRecordingCaptureActive())
        if (shouldCancel) {
            cancelCurrentSession()
        }
    }

    private fun isRecordingCaptureActive(): Boolean {
        if (!this::asrSessionManager.isInitialized) return false
        return asrSessionManager.isRecordingActive()
    }

    // ==================== 菜单动作 ====================

    private fun hideRadialMenu() {
        menuController.hideRadialMenu()
        updateVisibilityByPref("hide_radial_menu")
    }

    private fun hideVendorMenu() {
        menuController.hideVendorMenu()
        updateVisibilityByPref("hide_vendor_menu")
    }

    private fun onPickPromptPresetFromMenu() {
        touchActiveGuard = true
        hideVendorMenu()

        val center = viewManager.getBallCenterSnapshot()
        val alpha = getMenuAlphaOrDefault()

        val presets = try {
            prefs.getPromptPresets()
        } catch (e: Throwable) {
            Log.e(tag, "Failed to get prompt presets", e)
            emptyList()
        }
        val active = try {
            prefs.activePromptId
        } catch (e: Throwable) {
            Log.w(tag, "Failed to get active prompt ID", e)
            ""
        }

        val entries = presets.map { p ->
            Triple(p.title, p.id == active) {
                try {
                    prefs.activePromptId = p.id
                    showToast(context.getString(R.string.switched_preset, p.title))
                } catch (e: Throwable) {
                    Log.e(tag, "Failed to switch prompt preset", e)
                }
                Unit
            }
        }

        menuController.showListPanel(
            anchorCenter = center,
            alpha = alpha,
            title = context.getString(R.string.label_llm_prompt_presets),
            entries = entries
        ) {
            touchActiveGuard = false
            updateVisibilityByPref("prompt_panel_dismiss")
        }
    }

    /** 长按菜单「分发识别继续」：弹出三选项列表面板，点选即生效。 */
    private fun showDispatchContinuePanelFromMenu() {
        touchActiveGuard = true
        val center = viewManager.getBallCenterSnapshot()
        val alpha = getMenuAlphaOrDefault()
        val current = readDispatchContinueMode()

        val entries = listOf(
            Triple(context.getString(R.string.label_dispatch_continue_none), current == Prefs.DispatchContinueMode.NONE) {
                applyDispatchContinueModeFromMenu(Prefs.DispatchContinueMode.NONE)
            },
            Triple(context.getString(R.string.label_dispatch_continue_always), current == Prefs.DispatchContinueMode.ALWAYS) {
                applyDispatchContinueModeFromMenu(Prefs.DispatchContinueMode.ALWAYS)
            },
            Triple(context.getString(R.string.label_dispatch_continue_on_miss), current == Prefs.DispatchContinueMode.ON_MISS) {
                applyDispatchContinueModeFromMenu(Prefs.DispatchContinueMode.ON_MISS)
            }
        )

        menuController.showListPanel(
            anchorCenter = center,
            alpha = alpha,
            title = context.getString(R.string.label_dispatch_continue),
            entries = entries
        ) {
            touchActiveGuard = false
            updateVisibilityByPref("dispatch_continue_panel_dismiss")
        }
    }

    private fun applyDispatchContinueModeFromMenu(mode: Prefs.DispatchContinueMode) {
        try {
            prefs.dispatchContinueMode = mode
        } catch (e: Throwable) {
            Log.w(tag, "Failed to save dispatch continue mode", e)
        }
        val label = context.getString(
            when (mode) {
                Prefs.DispatchContinueMode.NONE -> R.string.label_dispatch_continue_none
                Prefs.DispatchContinueMode.ALWAYS -> R.string.label_dispatch_continue_always
                Prefs.DispatchContinueMode.ON_MISS -> R.string.label_dispatch_continue_on_miss
            }
        )
        showToast(context.getString(R.string.toast_dispatch_continue_switched, label))
    }

    private fun onPickAsrVendor() {
        touchActiveGuard = true
        hideVendorMenu()

        val center = viewManager.getBallCenterSnapshot()
        val alpha = getMenuAlphaOrDefault()

        val vendors = AsrVendorUi.pairs(context)
        val cur = try {
            prefs.asrVendor
        } catch (e: Throwable) {
            Log.w(tag, "Failed to get current vendor", e)
            AsrVendor.Volc
        }

        val entries = vendors.map { (v, name) ->
            Triple(name, v == cur) {
                try {
                    val old = try {
                        prefs.asrVendor
                    } catch (e: Throwable) {
                        Log.w(tag, "Failed to get old vendor", e)
                        AsrVendor.Volc
                    }
                    if (v != old) {
                        prefs.asrVendor = v
                        // 离开本地引擎时卸载缓存识别器，释放内存
                        if (old == AsrVendor.SenseVoice && v != AsrVendor.SenseVoice) {
                            try {
                                com.brycewg.asrkb.asr.unloadSenseVoiceRecognizer()
                            } catch (e: Throwable) {
                                Log.e(tag, "Failed to unload SenseVoice", e)
                            }
                        }
                        if (old == AsrVendor.FunAsrNano && v != AsrVendor.FunAsrNano) {
                            try {
                                com.brycewg.asrkb.asr.unloadFunAsrNanoRecognizer()
                            } catch (e: Throwable) {
                                Log.e(tag, "Failed to unload FunASR Nano", e)
                            }
                        }
                        if (old == AsrVendor.Qwen3Asr && v != AsrVendor.Qwen3Asr) {
                            try {
                                com.brycewg.asrkb.asr.unloadQwen3AsrRecognizer()
                            } catch (e: Throwable) {
                                Log.e(tag, "Failed to unload Qwen3-ASR", e)
                            }
                        }
                        if (old == AsrVendor.Parakeet && v != AsrVendor.Parakeet) {
                            try {
                                com.brycewg.asrkb.asr.unloadParakeetRecognizer()
                            } catch (e: Throwable) {
                                Log.e(tag, "Failed to unload Parakeet", e)
                            }
                        }
                        if (old == AsrVendor.FireRedAsr && v != AsrVendor.FireRedAsr) {
                            try {
                                com.brycewg.asrkb.asr.unloadFireRedAsrRecognizer()
                            } catch (e: Throwable) {
                                Log.e(tag, "Failed to unload FireRedASR", e)
                            }
                        }
                        if (old == AsrVendor.XAsr && v != AsrVendor.XAsr) {
                            try {
                                com.brycewg.asrkb.asr.unloadXAsrRecognizer()
                            } catch (e: Throwable) {
                                Log.e(tag, "Failed to unload X-ASR", e)
                            }
                        }

                        // 切换到本地引擎且启用预加载时，触发预加载以降低首次等待
                        if (v == AsrVendor.SenseVoice && prefs.svPreloadEnabled) {
                            try {
                                com.brycewg.asrkb.asr.preloadSenseVoiceIfConfigured(context, prefs)
                            } catch (e: Throwable) {
                                Log.e(tag, "Failed to preload SenseVoice", e)
                            }
                        }
                        if (v == AsrVendor.FunAsrNano && prefs.fnPreloadEnabled) {
                            try {
                                com.brycewg.asrkb.asr.preloadFunAsrNanoIfConfigured(context, prefs)
                            } catch (e: Throwable) {
                                Log.e(tag, "Failed to preload FunASR Nano", e)
                            }
                        }
                        if (v == AsrVendor.Qwen3Asr && prefs.qwPreloadEnabled) {
                            try {
                                com.brycewg.asrkb.asr.preloadQwen3AsrIfConfigured(context, prefs)
                            } catch (e: Throwable) {
                                Log.e(tag, "Failed to preload Qwen3-ASR", e)
                            }
                        }
                        if (v == AsrVendor.Parakeet && prefs.pkPreloadEnabled) {
                            try {
                                com.brycewg.asrkb.asr.preloadParakeetIfConfigured(context, prefs)
                            } catch (e: Throwable) {
                                Log.e(tag, "Failed to preload Parakeet", e)
                            }
                        }
                        if (v == AsrVendor.FireRedAsr && prefs.frPreloadEnabled) {
                            try {
                                com.brycewg.asrkb.asr.preloadFireRedAsrIfConfigured(context, prefs)
                            } catch (e: Throwable) {
                                Log.e(tag, "Failed to preload FireRedASR", e)
                            }
                        }
                        if (v == AsrVendor.XAsr && prefs.xAsrPreloadEnabled) {
                            try {
                                com.brycewg.asrkb.asr.preloadXAsrIfConfigured(context, prefs)
                            } catch (e: Throwable) {
                                Log.e(tag, "Failed to preload X-ASR", e)
                            }
                        }
                    }
                    showToast(name)
                } catch (e: Throwable) {
                    Log.e(tag, "Failed to switch ASR vendor", e)
                }
                Unit
            }
        }

        menuController.showListPanel(
            anchorCenter = center,
            alpha = alpha,
            title = context.getString(R.string.label_choose_asr_vendor),
            entries = entries
        ) {
            touchActiveGuard = false
            updateVisibilityByPref("vendor_panel_dismiss")
        }
    }

    private fun enableMoveModeFromMenu() {
        stateMachine.transitionTo(FloatingBallState.MoveMode)
        hideVendorMenu()
        showToast(context.getString(R.string.toast_move_mode_on))
    }

    private fun togglePostprocFromMenu() {
        try {
            val newVal = !prefs.postProcessEnabled
            prefs.postProcessEnabled = newVal
            val msg = context.getString(
                R.string.status_postproc,
                if (newVal) {
                    context.getString(
                        R.string.toggle_on
                    )
                } else {
                    context.getString(R.string.toggle_off)
                }
            )
            showToast(msg)
        } catch (e: Throwable) {
            Log.e(tag, "Failed to toggle postproc", e)
        }
    }

    /** 长按菜单「启用语音唤醒」：直接切换；启停服务与设置页行为一致，设置页开关显示服务实况会自动跟上。 */
    private fun toggleWakeWordFromMenu() {
        try {
            val target = !prefs.wakeWordEnabled
            if (target && !hasRecordAudioPermission()) {
                showToast(context.getString(R.string.asr_error_mic_permission_denied))
                return
            }
            prefs.wakeWordEnabled = target
            if (target) {
                com.brycewg.asrkb.wake.WakeWordService.start(context)
            } else {
                com.brycewg.asrkb.wake.WakeWordService.stop(context)
            }
            showToast(
                context.getString(
                    R.string.toast_wake_word_switched,
                    context.getString(if (target) R.string.toggle_on else R.string.toggle_off)
                )
            )
        } catch (e: Throwable) {
            Log.e(tag, "Failed to toggle wake word", e)
        }
    }

    /** 长按菜单「启用语音播报」：直接切换；各播报入口现读偏好，即时生效。 */
    private fun toggleTtsAnnounceFromMenu() {
        try {
            val target = !prefs.ttsEnabled
            prefs.ttsEnabled = target
            showToast(
                context.getString(
                    R.string.toast_tts_switched,
                    context.getString(if (target) R.string.toggle_on else R.string.toggle_off)
                )
            )
        } catch (e: Throwable) {
            Log.e(tag, "Failed to toggle tts announce", e)
        }
    }

    private fun showHistoryPanelFromMenu() {
        touchActiveGuard = true
        hideVendorMenu()

        val center = viewManager.getBallCenterSnapshot()
        val alpha = getMenuAlphaOrDefault()
        val emptyText = context.getString(R.string.empty_history)
        val title = context.getString(R.string.btn_open_asr_history)

        scope.launch(Dispatchers.IO) {
            val texts: List<String> = try {
                com.brycewg.asrkb.store.AsrHistoryStore(context)
                    .listRecent(100)
                    .map { it.text }
                    .filter { it.isNotBlank() }
            } catch (e: Throwable) {
                Log.e(tag, "Failed to load ASR history for panel", e)
                emptyList()
            }
            handler.post {
                menuController.showScrollableTextPanel(
                    anchorCenter = center,
                    alpha = alpha,
                    title = title,
                    texts = if (texts.isEmpty()) listOf(emptyText) else texts,
                    onItemClick = { text ->
                        if (text == emptyText) {
                            return@showScrollableTextPanel
                        }
                        try {
                            val clipMgr = context.getSystemService(
                                android.content.ClipboardManager::class.java
                            )
                            val clip = android.content.ClipData.newPlainText("asr_history", text)
                            clipMgr?.setPrimaryClip(clip)
                            showToast(context.getString(R.string.floating_asr_copied))
                        } catch (e: Throwable) {
                            Log.e(tag, "Failed to copy history text", e)
                        }
                    },
                    initialVisibleCount = 20,
                    loadMoreCount = 20
                ) {
                    touchActiveGuard = false
                    updateVisibilityByPref("history_panel_dismiss")
                }
            }
        }
    }

    /** 长按菜单「录音自动停止方式」：弹出三选项列表面板，点选即生效，与设置页双向实时同步。 */
    private fun showRecordingAutoStopPickerFromMenu() {
        touchActiveGuard = true
        val center = viewManager.getBallCenterSnapshot()
        val alpha = getMenuAlphaOrDefault()
        val current = try {
            prefs.recordingAutoStopMode
        } catch (e: Throwable) {
            Log.w(tag, "Failed to read recording auto stop mode", e)
            Prefs.RecordingAutoStopMode.MANUAL
        }

        val entries = listOf(
            Triple(context.getString(R.string.option_recording_auto_stop_manual), current == Prefs.RecordingAutoStopMode.MANUAL) {
                applyRecordingAutoStopModeFromMenu(Prefs.RecordingAutoStopMode.MANUAL)
            },
            Triple(context.getString(R.string.option_recording_auto_stop_silence), current == Prefs.RecordingAutoStopMode.SILENCE) {
                applyRecordingAutoStopModeFromMenu(Prefs.RecordingAutoStopMode.SILENCE)
            },
            Triple(context.getString(R.string.option_recording_auto_stop_max_duration), current == Prefs.RecordingAutoStopMode.MAX_DURATION) {
                applyRecordingAutoStopModeFromMenu(Prefs.RecordingAutoStopMode.MAX_DURATION)
            }
        )

        menuController.showListPanel(
            anchorCenter = center,
            alpha = alpha,
            title = context.getString(R.string.label_recording_auto_stop_mode),
            entries = entries
        ) {
            touchActiveGuard = false
            updateVisibilityByPref("recording_auto_stop_panel_dismiss")
        }
    }

    private fun applyRecordingAutoStopModeFromMenu(mode: Prefs.RecordingAutoStopMode) {
        try {
            val old = try {
                prefs.recordingAutoStopMode
            } catch (e: Throwable) {
                Log.w(tag, "Failed to read old recording auto stop mode", e)
                Prefs.RecordingAutoStopMode.MANUAL
            }
            if (mode != old) {
                prefs.recordingAutoStopMode = mode
                // 切到停说判停时预热 VAD，降低首次判停延迟（与设置页行为一致）
                if (mode == Prefs.RecordingAutoStopMode.SILENCE) {
                    try {
                        com.brycewg.asrkb.asr.VadDetector.preload(
                            context.applicationContext,
                            16000,
                            prefs.autoStopSilenceSensitivity
                        )
                    } catch (e: Throwable) {
                        Log.w(tag, "Failed to preload VAD", e)
                    }
                }
            }
            val label = context.getString(
                when (mode) {
                    Prefs.RecordingAutoStopMode.MANUAL -> R.string.option_recording_auto_stop_manual
                    Prefs.RecordingAutoStopMode.SILENCE -> R.string.option_recording_auto_stop_silence
                    Prefs.RecordingAutoStopMode.MAX_DURATION -> R.string.option_recording_auto_stop_max_duration
                }
            )
            showToast(context.getString(R.string.toast_recording_auto_stop_switched, label))
        } catch (e: Throwable) {
            Log.e(tag, "Failed to switch recording auto stop mode", e)
        }
    }

    private fun openSettingsFromMenu() {
        try {
            val intent = Intent(context, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Throwable) {
            Log.e(tag, "Failed to open settings", e)
        }
    }

    private fun uploadClipboardOnceFromMenu() {
        try {
            val mgr = com.brycewg.asrkb.clipboard.SyncClipboardManager(
                context,
                prefs,
                scope,
                clipboardPort = com.brycewg.asrkb.clipboard.SystemClipboardPortFactory.create(
                    context,
                    prefs
                )
            )
            scope.launch(Dispatchers.IO) {
                val ok = try {
                    mgr.uploadOnce()
                } catch (t: Throwable) {
                    Log.e(tag, "Failed to upload clipboard", t)
                    false
                }
                handler.post {
                    showToast(
                        context.getString(
                            if (ok) R.string.sc_status_uploaded else R.string.sc_test_failed
                        )
                    )
                }
            }
        } catch (e: Throwable) {
            Log.e(tag, "Failed to init clipboard manager for upload", e)
        }
    }

    private fun pullClipboardOnceFromMenu() {
        try {
            val mgr = com.brycewg.asrkb.clipboard.SyncClipboardManager(
                context,
                prefs,
                scope,
                clipboardPort = com.brycewg.asrkb.clipboard.SystemClipboardPortFactory.create(
                    context,
                    prefs
                )
            )
            scope.launch(Dispatchers.IO) {
                val ok = try {
                    mgr.pullNow(updateClipboard = true).first
                } catch (t: Throwable) {
                    Log.e(tag, "Failed to pull clipboard", t)
                    false
                }

                handler.post {
                    val msgRes = if (ok) R.string.sc_test_success else R.string.sc_test_failed
                    showToast(context.getString(msgRes))
                }
            }
        } catch (e: Throwable) {
            Log.e(tag, "Failed to init clipboard manager for pull", e)
        }
    }

    private fun getMenuAlphaOrDefault(): Float = try {
        prefs.floatingSwitcherAlpha
    } catch (e: Throwable) {
        Log.w(tag, "Failed to get floating menu alpha", e)
        1.0f
    }

    // 面板菜单项 = 数据源（悬浮球面板设置页维护的有序 id 列表）逐项构建；每次长按现读现建，改完即生效
    private fun buildRadialMenuItems(): List<FloatingMenuHelper.MenuItem> =
        FloatingPanelItemId.loadOrder(prefs).mapNotNull { buildMenuItem(it) }

    /** 按 id 构建单个菜单项；条件项不满足（剪贴板同步关闭）时返回 null。 */
    private fun buildMenuItem(id: FloatingPanelItemId): FloatingMenuHelper.MenuItem? {
        if (id == FloatingPanelItemId.ClipboardUpload || id == FloatingPanelItemId.ClipboardPull) {
            val syncEnabled = try {
                prefs.syncClipboardEnabled
            } catch (_: Throwable) {
                false
            }
            if (!syncEnabled) return null
        }
        val label = context.getString(id.labelRes)
        val iconRes = when (id) {
            FloatingPanelItemId.DispatchContinue ->
                if (readDispatchContinueMode() == Prefs.DispatchContinueMode.NONE) {
                    R.drawable.circles_four
                } else {
                    R.drawable.circles_four_fill
                }

            FloatingPanelItemId.SilenceAutoStop -> {
                val mode = try {
                    prefs.recordingAutoStopMode
                } catch (_: Throwable) {
                    Prefs.RecordingAutoStopMode.MANUAL
                }
                if (mode != Prefs.RecordingAutoStopMode.MANUAL) {
                    R.drawable.hand_palm_fill
                } else {
                    R.drawable.hand_palm
                }
            }

            FloatingPanelItemId.PostProc ->
                if (try {
                        prefs.postProcessEnabled
                    } catch (_: Throwable) {
                        false
                    }
                ) {
                    R.drawable.magic_wand_fill
                } else {
                    R.drawable.magic_wand
                }

            FloatingPanelItemId.WakeWord ->
                if (try {
                        prefs.wakeWordEnabled
                    } catch (_: Throwable) {
                        false
                    }
                ) {
                    R.drawable.microphone_fill
                } else {
                    R.drawable.microphone
                }

            FloatingPanelItemId.TtsAnnounce ->
                if (try {
                        prefs.ttsEnabled
                    } catch (_: Throwable) {
                        false
                    }
                ) {
                    R.drawable.speaker_high_fill
                } else {
                    R.drawable.speaker_high
                }

            else -> id.iconRes
        }
        return FloatingMenuHelper.MenuItem(iconRes, label, label) {
            when (id) {
                FloatingPanelItemId.DispatchContinue -> showDispatchContinuePanelFromMenu()
                FloatingPanelItemId.SwitchPrompt -> onPickPromptPresetFromMenu()
                FloatingPanelItemId.SwitchAsr -> onPickAsrVendor()
                FloatingPanelItemId.MoveBall -> enableMoveModeFromMenu()
                FloatingPanelItemId.SilenceAutoStop -> showRecordingAutoStopPickerFromMenu()
                FloatingPanelItemId.PostProc -> togglePostprocFromMenu()
                FloatingPanelItemId.WakeWord -> toggleWakeWordFromMenu()
                FloatingPanelItemId.TtsAnnounce -> toggleTtsAnnounceFromMenu()
                FloatingPanelItemId.History -> showHistoryPanelFromMenu()
                FloatingPanelItemId.ClipboardUpload -> uploadClipboardOnceFromMenu()
                FloatingPanelItemId.ClipboardPull -> pullClipboardOnceFromMenu()
                FloatingPanelItemId.Settings -> openSettingsFromMenu()
            }
        }
    }

    // ==================== 辅助方法 ====================

    private fun showToast(message: String) {
        try {
            notifier.showToast(message)
        } catch (e: Throwable) {
            Log.e(tag, "Failed to show toast: $message", e)
        }
    }

    private fun showVolumeKeyStatusToast(messageRes: Int) {
        if (!prefs.volumeKeyStatusToastEnabled) return
        showToast(context.getString(messageRes))
    }

    private fun hasRecordAudioPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun hapticTapIfEnabled(view: View?) {
        HapticFeedbackHelper.performTap(context, prefs, view)
    }
}
