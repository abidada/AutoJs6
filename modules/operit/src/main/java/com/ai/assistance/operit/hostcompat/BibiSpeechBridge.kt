package com.ai.assistance.operit.hostcompat

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.api.speech.SpeechService
import com.ai.assistance.operit.api.voice.VoiceService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Operit 语音栈 → bibi 桥（C15，P4.3 实装）。
 *
 * 结构：模块保留上游 [SpeechService] / [VoiceService] 接口面（UI 零改动），实现转发到宿主
 * 注入的 [HostSpeechBackend]（由宿主映射到 bibi 的 ASR / TTS 编排器）。本文件只负责：
 *  1. 把上游 flow/coroutine 接口翻译成后端调用；
 *  2. 把后端回调（[HostSpeechBackend.SttListener]）驱动成本类的 StateFlow；
 *  3. 宿主未注入后端时全部能力降级为不可用（initialize=false / speak=false）——
 *     与 P1 骨架行为一致，UI 渲染其既有「不可用」态而非崩溃。
 *
 * 麦克风独占权在 bibi（C18）：本桥不持有任何音频设备，只转发会话意图。
 *
 * The two interfaces cannot be implemented by one class (their `isInitialized` members have
 * different types), so the facade hands out one impl per interface via [asSpeechService] /
 * [asVoiceService]. Factories in api/speech / api/voice return these.
 *
 * Upstream sync rule: interface changes in api/speech/SpeechService.kt or
 * api/voice/VoiceService.kt must be reflected here.
 */
object BibiSpeechBridge {

    fun getInstance(context: Context): BibiSpeechBridge = this

    fun asSpeechService(context: Context): BibiSttBridge =
        BibiSttBridge.getInstance(context)

    fun asVoiceService(context: Context): BibiTtsBridge =
        BibiTtsBridge.getInstance(context)

    const val DISABLED_REASON =
        "Operit speech stack is bridged to bibi (C15); host backend not registered"
}

/**
 * 后端会话状态常量（与 bibi `ExternalSpeechSessionState` 对齐，供 Listener 使用）。
 * 模块侧不依赖 bibi 类型，故自带一份语义等价常量。
 */
object HostSpeechSessionState {
    const val IDLE = 0
    const val RECORDING = 1
    const val PROCESSING = 2
    const val ERROR = 3
}

/**
 * STT 桥：`SpeechService` → [HostSpeechBackend]。
 *
 * StateFlow 由后端回调驱动；`isRecognizing` / `currentState` 需要同步读，用一个
 * volatile 镜像字段维护（StateFlow 本身不提供同步读）。
 */
class BibiSttBridge private constructor(
    @Suppress("UNUSED_PARAMETER") context: Context
) : SpeechService, HostSpeechBackend.SttListener {

    companion object {
        private const val TAG = "BibiSttBridge"

        @Volatile
        private var instance: BibiSttBridge? = null

        fun getInstance(context: Context): BibiSttBridge =
            instance ?: synchronized(this) {
                instance ?: BibiSttBridge(context.applicationContext).also { instance = it }
            }
    }

    private val initializedFlow = MutableStateFlow(false)
    private val stateFlow =
        MutableStateFlow(SpeechService.RecognitionState.UNINITIALIZED)
    private val resultFlow =
        MutableStateFlow(SpeechService.RecognitionResult("", isFinal = true))
    private val errorFlow =
        MutableStateFlow(SpeechService.RecognitionError(-1, BibiSpeechBridge.DISABLED_REASON))
    private val volumeFlow = MutableStateFlow(0f)

    @Volatile
    private var recognizing: Boolean = false

    /** 后端是否已安装监听器（避免重复 setSttListener 覆盖宿主既有监听）。 */
    private var listenerAttached = false

    override val isInitialized: StateFlow<Boolean> = initializedFlow
    override val isRecognizing: Boolean
        get() = recognizing
    override val currentState: SpeechService.RecognitionState
        get() = stateFlow.value
    override val recognitionStateFlow: StateFlow<SpeechService.RecognitionState> = stateFlow
    override val recognitionResultFlow: StateFlow<SpeechService.RecognitionResult> = resultFlow
    override val recognitionErrorFlow: StateFlow<SpeechService.RecognitionError> = errorFlow
    override val volumeLevelFlow: StateFlow<Float> = volumeFlow

    override suspend fun initialize(): Boolean {
        val backend = OperitLibrary.speechBackend
        if (backend == null) {
            AppLogger.w(TAG, "initialize: ${BibiSpeechBridge.DISABLED_REASON}")
            initializedFlow.value = false
            stateFlow.value = SpeechService.RecognitionState.UNINITIALIZED
            errorFlow.value = SpeechService.RecognitionError(-1, BibiSpeechBridge.DISABLED_REASON)
            return false
        }
        attachListener(backend)
        val ready = backend.isSttReady()
        initializedFlow.value = ready
        stateFlow.value = if (ready) {
            SpeechService.RecognitionState.IDLE
        } else {
            SpeechService.RecognitionState.UNINITIALIZED
        }
        if (!ready) {
            errorFlow.value =
                SpeechService.RecognitionError(-1, "bibi ASR not ready (model/permission)")
        }
        return ready
    }

    override suspend fun startRecognition(
        languageCode: String,
        continuousMode: Boolean,
        partialResults: Boolean,
        audioSource: Int
    ): Boolean {
        @Suppress("UNUSED_EXPRESSION")
        audioSource
        val backend = OperitLibrary.speechBackend
        if (backend == null) {
            AppLogger.w(TAG, "startRecognition: no backend")
            errorFlow.value = SpeechService.RecognitionError(-1, BibiSpeechBridge.DISABLED_REASON)
            return false
        }
        attachListener(backend)
        // 语言由 bibi 的 Prefs 决定（供应商/语言跟随应用内设置）；continuousMode 语义
        // 由宿主的会话生命周期承担（Operit 侧调用 stop/cancel 控制结束）。
        val ok = backend.startRecognition(languageCode, continuousMode, partialResults)
        if (ok) {
            recognizing = true
            errorFlow.value = SpeechService.RecognitionError(0, "")
        } else {
            errorFlow.value =
                SpeechService.RecognitionError(-2, "bibi rejected recognition session")
        }
        return ok
    }

    override suspend fun stopRecognition(): Boolean {
        val backend = OperitLibrary.speechBackend ?: return false
        return backend.stopRecognition()
    }

    override suspend fun cancelRecognition() {
        OperitLibrary.speechBackend?.cancelRecognition()
        recognizing = false
    }

    override fun shutdown() {
        runCatching { OperitLibrary.speechBackend?.cancelRecognition() }
        recognizing = false
        listenerAttached = false
    }

    override suspend fun getSupportedLanguages(): List<String> = emptyList()

    override suspend fun recognize(audioData: FloatArray) {
        @Suppress("UNUSED_EXPRESSION")
        audioData
    }

    private fun attachListener(backend: HostSpeechBackend) {
        if (listenerAttached) return
        backend.setSttListener(this)
        listenerAttached = true
    }

    // ---------------- HostSpeechBackend.SttListener（后端线程回调） ----------------

    override fun onRecognitionState(state: Int, message: String?) {
        stateFlow.value = when (state) {
            HostSpeechSessionState.IDLE -> SpeechService.RecognitionState.IDLE
            HostSpeechSessionState.RECORDING -> SpeechService.RecognitionState.RECOGNIZING
            HostSpeechSessionState.PROCESSING -> SpeechService.RecognitionState.PROCESSING
            HostSpeechSessionState.ERROR -> SpeechService.RecognitionState.ERROR
            else -> stateFlow.value
        }
        if (state != HostSpeechSessionState.RECORDING) recognizing = false
        if (state == HostSpeechSessionState.ERROR && message != null) {
            errorFlow.value = SpeechService.RecognitionError(-3, message)
        }
    }

    override fun onPartialResult(text: String) {
        resultFlow.value =
            SpeechService.RecognitionResult(text, isFinal = false, confidence = 1f)
    }

    override fun onFinalResult(text: String) {
        recognizing = false
        resultFlow.value =
            SpeechService.RecognitionResult(text, isFinal = true, confidence = 1f)
    }

    override fun onRecognitionError(code: Int, message: String) {
        recognizing = false
        stateFlow.value = SpeechService.RecognitionState.ERROR
        errorFlow.value = SpeechService.RecognitionError(code, message)
    }

    override fun onVolumeLevel(level: Float) {
        volumeFlow.value = level.coerceIn(0f, 1f)
    }
}

/**
 * TTS 桥：`VoiceService` → [HostSpeechBackend]。
 *
 * 宿主未注入后端时 `initialize()=false` / `speak()=false`，UI 维持「不可用」渲染。
 */
class BibiTtsBridge private constructor(
    @Suppress("UNUSED_PARAMETER") context: Context
) : VoiceService, HostSpeechBackend.TtsListener {

    companion object {
        private const val TAG = "BibiTtsBridge"

        @Volatile
        private var instance: BibiTtsBridge? = null

        fun getInstance(context: Context): BibiTtsBridge =
            instance ?: synchronized(this) {
                instance ?: BibiTtsBridge(context.applicationContext).also { instance = it }
            }
    }

    private val speakingFlow = MutableStateFlow(false)

    @Volatile
    private var initialized: Boolean = false

    @Volatile
    private var speaking: Boolean = false

    private var listenerAttached = false

    override val isInitialized: Boolean
        get() = initialized
    override val isSpeaking: Boolean
        get() = speaking
    override val speakingStateFlow: StateFlow<Boolean> = speakingFlow

    override suspend fun initialize(): Boolean {
        val backend = OperitLibrary.speechBackend
        if (backend == null) {
            AppLogger.w(TAG, "initialize: ${BibiSpeechBridge.DISABLED_REASON}")
            initialized = false
            return false
        }
        attachListener(backend)
        initialized = backend.isTtsReady()
        return initialized
    }

    override suspend fun speak(
        text: String,
        interrupt: Boolean,
        rate: Float?,
        pitch: Float?,
        extraParams: Map<String, String>
    ): Boolean {
        @Suppress("UNUSED_EXPRESSION")
        extraParams
        val backend = OperitLibrary.speechBackend
        if (backend == null) {
            AppLogger.w(TAG, "speak: no backend")
            return false
        }
        attachListener(backend)
        val ok = backend.speak(text, interrupt, rate, pitch)
        if (ok) {
            speaking = true
            speakingFlow.value = true
        }
        return ok
    }

    override suspend fun stop(): Boolean {
        val backend = OperitLibrary.speechBackend ?: return false
        val ok = backend.stopSpeaking()
        speaking = false
        speakingFlow.value = false
        return ok
    }

    override suspend fun pause(): Boolean = false

    override suspend fun resume(): Boolean = false

    override fun shutdown() {
        runCatching { OperitLibrary.speechBackend?.stopSpeaking() }
        speaking = false
        speakingFlow.value = false
        listenerAttached = false
    }

    override suspend fun getAvailableVoices(): List<VoiceService.Voice> = emptyList()

    override suspend fun setVoice(voiceId: String): Boolean {
        // 音色选择发生在 bibi 侧设置页；Operit 侧仅透传不可用。
        @Suppress("UNUSED_PARAMETER")
        voiceId
        return false
    }

    private fun attachListener(backend: HostSpeechBackend) {
        if (listenerAttached) return
        backend.setTtsListener(this)
        listenerAttached = true
    }

    // ---------------- HostSpeechBackend.TtsListener ----------------

    override fun onSpeakingChanged(speaking: Boolean) {
        this.speaking = speaking
        speakingFlow.value = speaking
    }
}
