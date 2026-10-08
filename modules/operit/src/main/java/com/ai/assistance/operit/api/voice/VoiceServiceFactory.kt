package com.ai.assistance.operit.api.voice

import android.content.Context
import com.ai.assistance.operit.data.preferences.SpeechServiceProfilesPreferences
import com.ai.assistance.operit.hostcompat.BibiSpeechBridge

/** 语音服务工厂，用于创建不同类型的语音服务实例 */
object VoiceServiceFactory {
    /** 语音服务类型枚举（保留：设置存储与上游 UI 仍引用） */
    enum class VoiceServiceType {
        /** 基于Android系统TTS的简单语音实现 */
        SIMPLE_TTS,
        /** 基于HTTP请求的TTS实现 */
        HTTP_TTS,
        /** 基于 OpenAI Realtime WebSocket 的 TTS 实现 */
        OPENAI_WS_TTS,
        /** 硅基流动TTS服务 */
        SILICONFLOW_TTS,
        /** MiniMax TTS 服务 */
        MINIMAX_TTS,
        /** MiMo TTS 服务 */
        MIMO_TTS,
        /** 豆包 TTS 服务 */
        DOUBAO_TTS,
        OPENAI_TTS,
        /** 基于 VITS/Piper ONNX Runtime 推理形态的本地 TTS 服务 */
        VITS_TTS,
    }

    /**
     * 创建语音服务实例 (现在从Preferences中读取配置)
     *
     * Operit port (C15): upstream TTS engine providers were trimmed; every type resolves to
     * the bibi bridge (P4.3 wires real delegation). Profile storage keeps loading.
     *
     * @param context 应用上下文
     * @return 对应类型的VoiceService实例
     */
    fun createVoiceService(
        context: Context
    ): VoiceService {
        val profiles = SpeechServiceProfilesPreferences(context)
        val profile = kotlinx.coroutines.runBlocking { profiles.getCurrentTtsProfile() }
        @Suppress("UNUSED_EXPRESSION")
        profile
        return BibiSpeechBridge.asVoiceService(context)
    }

    // 单例实例缓存
    private var instance: VoiceService? = null
    private var currentProfileId: String? = null

    /**
     * 获取语音服务单例实例
     *
     * @param context 应用上下文
     * @return VoiceService实例
     */
    fun getInstance(context: Context): VoiceService {
        val profiles = SpeechServiceProfilesPreferences(context)
        val selectedProfileId = kotlinx.coroutines.runBlocking { profiles.getCurrentTtsProfile().id }

        if (instance == null || selectedProfileId != currentProfileId) {
            runCatching { instance?.shutdown() }
            instance = createVoiceService(context)
            currentProfileId = selectedProfileId
        }
        return instance ?: createVoiceService(context)
    }

    /** 重置单例实例 在需要更改语音服务类型或释放资源时调用 */
    fun resetInstance() {
        runCatching { instance?.shutdown() }
        instance = null
        currentProfileId = null
    }
}
