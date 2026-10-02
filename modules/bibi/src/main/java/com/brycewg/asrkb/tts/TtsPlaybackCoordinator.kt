/**
 * TTS 播报编排器：全应用唯一播报入口。
 *
 * 职责：
 * - 单工作线程串行播放；反馈类播报「最新优先」（新请求打断/替换旧请求）；
 * - 与开麦闸口联动：isBusy + runWhenIdle 供自动续听在播报结束后再重开麦克风；
 * - 场景开关（总开关/命中/未命中/错误/结果）在播放时统一校验；
 * - 播报结束按 keep-alive 配置调度引擎卸载。
 *
 * 线程模型：speak/stopSpeaking 任意线程可调；onFinished 与 runWhenIdle 回调固定主线程。
 *
 * 归属模块：tts
 */
package com.brycewg.asrkb.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.brycewg.asrkb.store.Prefs
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object TtsPlaybackCoordinator {
    private const val TAG = "TtsCoordinator"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bibi-tts").apply { isDaemon = true }
    }

    private val lock = Any()

    @Volatile private var appContext: Context? = null

    private var pending: SpeakRequest? = null
    private var playing: Boolean = false
    private val idleListeners = mutableListOf<() -> Unit>()

    private class SpeakRequest(
        val text: String,
        val bypassToggle: Boolean,
        val onFinished: ((success: Boolean) -> Unit)?
    )

    fun ensureInit(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    /** 是否有播报在播或待播（开麦闸口判据） */
    val isBusy: Boolean
        get() = synchronized(lock) { playing || pending != null }

    /**
     * 发起播报（最新优先：新请求会打断当前播放并替换待播请求）。
     *
     * @param bypassToggle true = 显式操作（如设置页试听），不受总开关限制
     * @param onFinished 主线程回调；被替换/失败时以 false 结束
     */
    fun speak(
        context: Context,
        text: String,
        bypassToggle: Boolean = false,
        onFinished: ((success: Boolean) -> Unit)? = null
    ) {
        ensureInit(context)
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            onFinished?.let { cb -> mainHandler.post { runSafely { cb(false) } } }
            return
        }
        val request = SpeakRequest(trimmed, bypassToggle, onFinished)
        var startLoop = false
        synchronized(lock) {
            pending?.let { old ->
                mainHandler.post { runSafely { old.onFinished?.invoke(false) } }
            }
            pending = request
            if (!playing) {
                playing = true
                startLoop = true
            }
        }
        if (startLoop) executor.execute { drainLoop() }
    }

    /** 打断当前播报并清空待播请求；幂等，空闲时为 no-op */
    fun stopSpeaking() {
        synchronized(lock) {
            pending?.let { old ->
                mainHandler.post { runSafely { old.onFinished?.invoke(false) } }
            }
            pending = null
        }
        OfflineTtsManager.stopPlaying()
    }

    /**
     * 空闲时（立即）或播报结束后（一次性）在主线程执行 action。
     * 若期间 stopSpeaking/speak 重置了状态，action 仍会执行一次（语义为「回到空闲」）。
     */
    fun runWhenIdle(action: () -> Unit) {
        val runNow = synchronized(lock) {
            if (!playing && pending == null) {
                true
            } else {
                idleListeners.add(action)
                false
            }
        }
        if (runNow) {
            mainHandler.post { runSafely(action) }
        }
    }

    // ==================== 内部 ====================

    private fun drainLoop() {
        while (true) {
            val request = synchronized(lock) {
                val r = pending
                pending = null
                if (r == null) playing = false
                r
            }
            if (request == null) {
                notifyIdle()
                scheduleUnload()
                return
            }
            val success = playRequest(request)
            request.onFinished?.let { cb ->
                mainHandler.post { runSafely { cb(success) } }
            }
        }
    }

    private fun playRequest(request: SpeakRequest): Boolean {
        val startMs = android.os.SystemClock.elapsedRealtime()
        var ok = false
        try {
            val context = appContext ?: return false
            val prefs = Prefs(context)
            if (!request.bypassToggle && !prefs.ttsEnabled) {
                Log.d(TAG, "TTS disabled, drop request (${request.text.length} chars)")
                return false
            }
            val loadStartMs = android.os.SystemClock.elapsedRealtime()
            val engine = OfflineTtsManager.loadSync(context, prefs)
            val engineLoadMs = android.os.SystemClock.elapsedRealtime() - loadStartMs
            if (engine == null) {
                com.brycewg.asrkb.store.debug.DebugLogManager.log(
                    "tts",
                    "speak_no_engine",
                    data = mapOf("engineLoadMs" to engineLoadMs)
                )
                return false
            }
            engine.speak(request.text, prefs.ttsSpeed, object : TtsSpeakCallback {
                override fun onDone() {
                    ok = true
                }

                override fun onError(message: String) {
                    Log.w(TAG, "TTS speak error: $message")
                    ok = false
                }
            })
            return ok
        } catch (t: Throwable) {
            Log.e(TAG, "TTS playback failed", t)
            return false
        } finally {
            val totalMs = android.os.SystemClock.elapsedRealtime() - startMs
            try {
                com.brycewg.asrkb.store.debug.DebugLogManager.log(
                    "tts",
                    "speak_done",
                    data = mapOf(
                        "chars" to request.text.length,
                        "ms" to totalMs,
                        "ok" to ok
                    )
                )
            } catch (_: Throwable) {
            }
            Log.d(TAG, "playRequest finished in ${totalMs}ms ok=$ok (${request.text.length} chars)")
        }
    }

    private fun notifyIdle() {
        val listeners = synchronized(lock) {
            if (idleListeners.isEmpty()) return
            val snapshot = idleListeners.toList()
            idleListeners.clear()
            snapshot
        }
        listeners.forEach { listener ->
            mainHandler.post { runSafely(listener) }
        }
    }

    private fun scheduleUnload() {
        val context = appContext ?: return
        try {
            OfflineTtsManager.scheduleAutoUnloadIfIdle(Prefs(context).ttsKeepAliveMinutes)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to schedule tts unload", t)
        }
    }

    private fun runSafely(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "TTS callback failed", t)
        }
    }
}
