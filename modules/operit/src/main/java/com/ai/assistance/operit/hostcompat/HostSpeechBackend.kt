package com.ai.assistance.operit.hostcompat

/**
 * Host speech delegation surface for P4.3 (C15).
 *
 * Operit's own STT/TTS/wake stack was trimmed (C15); speech is delegated to the host's
 * bibi ("说点啥") ASR/TTS stack, which owns the microphone (C18). The host injects a
 * concrete implementation through [OperitLibrary.setSpeechBackend]; until then
 * [BibiSpeechBridge] keeps implementing the upstream [com.ai.assistance.operit.api.speech.SpeechService]
 * / [com.ai.assistance.operit.api.voice.VoiceService] interfaces with graceful
 * unavailability (initialize=false / speak=false), so upstream UI keeps compiling
 * and rendering its "unavailable" states.
 *
 * The surface mirrors only what the two upstream interfaces actually expose
 * (flow-based recognition + fire-and-forget synthesis), so the host can bridge it
 * onto bibi without leaking bibi types into the module.
 */
interface HostSpeechBackend {

    // ---------------- STT (SpeechService) ----------------

    /** True when bibi's recognition stack is ready to accept [startRecognition]. */
    fun isSttReady(): Boolean

    /**
     * Starts a recognition session. Results are delivered through the callbacks below
     * (registered once via [setSttListener]) so the module can drive its StateFlows.
     */
    fun startRecognition(languageCode: String, continuousMode: Boolean, partialResults: Boolean): Boolean

    fun stopRecognition(): Boolean

    fun cancelRecognition()

    // ---------------- TTS (VoiceService) ----------------

    /** True when bibi's synthesis stack is ready to accept [speak]. */
    fun isTtsReady(): Boolean

    fun speak(text: String, interrupt: Boolean, rate: Float?, pitch: Float?): Boolean

    fun stopSpeaking(): Boolean

    // ---------------- Listener wire-up ----------------

    // 双槽位（D-7 修复）：上游 STT 与 TTS 是两个独立消费方（BibiSttBridge / BibiTtsBridge），
    // 单槽 setListener 会被后注册者覆盖,导致一方永久收不到回调。识别事件与播报事件按
    // 事件域正交路由,各走各的槽位。

    /** Installs the STT listener; module never calls back into bibi types. */
    fun setSttListener(listener: SttListener)

    /** Installs the TTS listener; module never calls back into bibi types. */
    fun setTtsListener(listener: TtsListener)

    interface SttListener {
        fun onRecognitionState(state: Int, message: String?)
        fun onPartialResult(text: String)
        fun onFinalResult(text: String)
        fun onRecognitionError(code: Int, message: String)
        fun onVolumeLevel(level: Float)
    }

    interface TtsListener {
        fun onSpeakingChanged(speaking: Boolean)
    }
}
