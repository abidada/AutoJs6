package org.autojs.autojs.host

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ai.assistance.operit.hostcompat.HostSpeechBackend
import com.ai.assistance.operit.hostcompat.HostSpeechSessionState
import com.brycewg.asrkb.host.AsrRecordingState
import com.brycewg.asrkb.host.AsrResultBroadcaster
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.tts.TtsPlaybackCoordinator
import com.brycewg.asrkb.ui.floating.FloatingAsrService

/**
 * Operit 模块的语音（STT/TTS）后端（P4.3 / C15）。
 *
 * 归属模块：host
 *
 * STT：复用 bibi 的**脚本识别链路**——`FloatingAsrService` 的 `ACTION_SCRIPT_START/STOP`
 * （与 `$bibi.start()` / `$bibi.stop()` 完全同链路，见 `runtime/api/augment/voice/Bibi.kt`），
 * 结果经 `AsrResultBroadcaster` 扇出到本桥的 Listener，再由模块侧 `BibiSttBridge` 驱动 StateFlow。
 * 语言/供应商跟随 bibi 应用内设置（模块只转发会话意图，不覆盖 Prefs）。
 *
 * TTS：走 bibi 的唯一播报入口 `TtsPlaybackCoordinator`（单工作线程串行 + 最新优先 + 与开麦闸口联动）。
 * `rate`/`pitch` 参数 bibi 侧由 Prefs 统一控制，本桥不逐次覆盖，保持设置页 1:1 语义。
 *
 * 麦克风独占权在 bibi（C18）：本桥不持有音频设备，仅转发；bibi 的 `AsrRecordingState`
 * 与 `TtsPlaybackCoordinator.isBusy` 作为就绪/忙碌判据。
 */
object AutoJsSpeechHostBridge : HostSpeechBackend {

    private const val TAG = "AutoJsSpeechBridge"

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var attached: Boolean = false

    @Volatile
    private var sttListener: HostSpeechBackend.SttListener? = null

    @Volatile
    private var ttsListener: HostSpeechBackend.TtsListener? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var sttSessionActive: Boolean = false

    /** 广播器侧监听器：bibi ASR 线程回调 → 转发给模块侧 SttListener。 */
    private val resultListener = object : AsrResultBroadcaster.Listener {
        override fun onPartial(text: String) {
            sttListener?.onPartialResult(text)
        }

        override fun onFinal(text: String) {
            sttSessionActive = false
            val l = sttListener ?: return
            l.onFinalResult(text)
            l.onRecognitionState(HostSpeechSessionState.IDLE, "final")
        }

        override fun onError(msg: String) {
            sttSessionActive = false
            val l = sttListener ?: return
            l.onRecognitionError(-500, msg)
            l.onRecognitionState(HostSpeechSessionState.ERROR, msg)
        }
    }

    /** 宿主注入上下文（App.onCreate 调用，早于模块 init）；幂等。 */
    @Synchronized
    fun attach(context: Context) {
        if (attached) return
        attached = true
        appContext = context.applicationContext
        AsrResultBroadcaster.add(resultListener)
        runCatching { TtsPlaybackCoordinator.ensureInit(context.applicationContext) }
    }

    // ---------------- STT ----------------

    override fun isSttReady(): Boolean {
        val ctx = appContext ?: return false
        // 就绪判据：麦克风权限已授予（bibi 启动会话时会自检，这里做前置可用性上报）
        return runCatching {
            androidx.core.content.ContextCompat.checkSelfPermission(
                ctx,
                android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    override fun startRecognition(
        languageCode: String,
        continuousMode: Boolean,
        partialResults: Boolean
    ): Boolean {
        @Suppress("UNUSED_EXPRESSION")
        languageCode
        @Suppress("UNUSED_EXPRESSION")
        partialResults
        val ctx = appContext ?: return false
        if (sttSessionActive) {
            Log.w(TAG, "startRecognition ignored: session already active")
            return false
        }
        if (!isSttReady()) {
            sttListener?.onRecognitionError(-401, "record permission denied")
            return false
        }
        // bibi 侧会话由悬浮球服务的脚本动作驱动；服务未启动时 startService 会拉起。
        return runCatching {
            val intent = Intent(ctx, FloatingAsrService::class.java).setAction(
                FloatingAsrService.ACTION_SCRIPT_START
            )
            ctx.startService(intent)
            sttSessionActive = true
            sttListener?.onRecognitionState(HostSpeechSessionState.RECORDING, "recording")
            true
        }.getOrElse { t ->
            Log.e(TAG, "startRecognition failed", t)
            sttListener?.onRecognitionError(-500, t.message ?: "start failed")
            false
        }
    }

    override fun stopRecognition(): Boolean {
        val ctx = appContext ?: return false
        return runCatching {
            ctx.startService(
                Intent(ctx, FloatingAsrService::class.java).setAction(
                    FloatingAsrService.ACTION_SCRIPT_STOP
                )
            )
            sttListener?.onRecognitionState(HostSpeechSessionState.PROCESSING, "processing")
            true
        }.getOrElse { t ->
            Log.e(TAG, "stopRecognition failed", t)
            false
        }
    }

    override fun cancelRecognition() {
        val ctx = appContext ?: return
        sttSessionActive = false
        runCatching {
            ctx.startService(
                Intent(ctx, FloatingAsrService::class.java).setAction(
                    FloatingAsrService.ACTION_SCRIPT_STOP
                )
            )
            sttListener?.onRecognitionState(HostSpeechSessionState.IDLE, "canceled")
        }.onFailure { Log.w(TAG, "cancelRecognition failed", it) }
    }

    // ---------------- TTS ----------------

    override fun isTtsReady(): Boolean {
        val ctx = appContext ?: return false
        // 总开关开启即视为可用（引擎按需加载）；未开启时 speak 会被 bibi 侧丢弃并回落 false。
        return runCatching { Prefs(ctx).ttsEnabled }.getOrDefault(false)
    }

    override fun speak(
        text: String,
        interrupt: Boolean,
        rate: Float?,
        pitch: Float?
    ): Boolean {
        @Suppress("UNUSED_EXPRESSION")
        interrupt
        @Suppress("UNUSED_EXPRESSION")
        rate
        @Suppress("UNUSED_EXPRESSION")
        pitch
        val ctx = appContext ?: return false
        if (text.isBlank()) return false
        return runCatching {
            mainHandler.post { ttsListener?.onSpeakingChanged(true) }
            TtsPlaybackCoordinator.speak(ctx, text) { success ->
                mainHandler.post { ttsListener?.onSpeakingChanged(false) }
                if (!success) Log.w(TAG, "TTS playback reported failure")
            }
            true
        }.getOrElse { t ->
            Log.e(TAG, "speak failed", t)
            mainHandler.post { ttsListener?.onSpeakingChanged(false) }
            false
        }
    }

    override fun stopSpeaking(): Boolean {
        return runCatching {
            TtsPlaybackCoordinator.stopSpeaking()
            mainHandler.post { ttsListener?.onSpeakingChanged(false) }
            true
        }.getOrElse { t ->
            Log.e(TAG, "stopSpeaking failed", t)
            false
        }
    }

    // ---------------- Listener ----------------

    override fun setSttListener(listener: HostSpeechBackend.SttListener) {
        this.sttListener = listener
    }

    override fun setTtsListener(listener: HostSpeechBackend.TtsListener) {
        this.ttsListener = listener
    }

    /** 诊断：bibi 侧当前是否有录音会话在跑。 */
    fun isBibiRecording(): Boolean = runCatching { AsrRecordingState.active }.getOrDefault(false)

    /** 诊断：bibi 侧是否有播报占用（供开麦闸口查询）。 */
    fun isBibiSpeaking(): Boolean = runCatching { TtsPlaybackCoordinator.isBusy }.getOrDefault(false)
}
