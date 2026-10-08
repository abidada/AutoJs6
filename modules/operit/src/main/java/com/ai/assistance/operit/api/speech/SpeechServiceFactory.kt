package com.ai.assistance.operit.api.speech

import android.content.Context
import com.ai.assistance.operit.data.preferences.SpeechServiceProfilesPreferences
import com.ai.assistance.operit.data.preferences.SpeechServicesPreferences.SttHttpConfig
import com.ai.assistance.operit.hostcompat.BibiSpeechBridge

/** 语音识别服务工厂类 用于创建和管理不同类型的语音识别服务 */
object SpeechServiceFactory {
    private const val TAG = "SpeechServiceFactory"

    /** 语音识别服务类型（枚举保留：设置存储与上游 UI 仍引用） */
    enum class SpeechServiceType {
        /** 基于Sherpa-ncnn的本地识别实现 */
        SHERPA_NCNN,
        OPENAI_STT,
        DEEPGRAM_STT,
    }

    /**
     * 创建语音识别服务实例
     *
     * Operit port (C15): upstream engine providers (Sherpa-NCNN / OpenAI / Deepgram) were
     * trimmed; every type resolves to the bibi bridge (P4.3 wires real delegation). Profile
     * storage keeps loading so settings data stays readable.
     *
     * @param context 应用上下文
     * @return 对应类型的语音识别服务实例
     */
    fun createSpeechService(
        context: Context
    ): SpeechService {
        val profiles = SpeechServiceProfilesPreferences(context)
        val profile = currentProfile(profiles)
        return createSpeechService(context, profile.serviceType, profile.httpConfig)
    }

    fun createWakeSpeechService(
        context: Context,
    ): SpeechService {
        // C15: wake word belongs to bibi KWS; the bridge stays inert until P4.3.
        val profiles = SpeechServiceProfilesPreferences(context)
        val profile = currentProfile(profiles)
        return createSpeechService(context, profile.serviceType, profile.httpConfig)
    }

    fun createSpeechService(
        context: Context,
        type: SpeechServiceType,
    ): SpeechService {
        val profiles = SpeechServiceProfilesPreferences(context)
        val config = currentProfile(profiles).httpConfig
        return createSpeechService(context, type, config)
    }

    private fun createSpeechService(
        context: Context,
        type: SpeechServiceType,
        httpConfig: SttHttpConfig,
    ): SpeechService {
        @Suppress("UNUSED_EXPRESSION")
        type
        @Suppress("UNUSED_EXPRESSION")
        httpConfig
        return BibiSpeechBridge.asSpeechService(context)
    }

    // 单例实例缓存（profile 变化即重建，与上游 getInstance 语义一致）
    private var instance: SpeechService? = null
    private var currentProfileId: String? = null

    /**
     * 获取语音识别服务单例实例
     *
     * @param context 应用上下文
     * @return 语音识别服务实例
     */
    fun getInstance(
        context: Context,
    ): SpeechService {
        val profiles = SpeechServiceProfilesPreferences(context)
        val profile = currentProfile(profiles)
        val selectedProfileId = profile.id

        if (instance == null || selectedProfileId != currentProfileId) {
            runCatching { instance?.shutdown() }
            instance = createSpeechService(context)
            currentProfileId = selectedProfileId
        }
        return instance ?: createSpeechService(context)
    }

    private fun currentProfile(
        profiles: SpeechServiceProfilesPreferences
    ): SpeechServiceProfilesPreferences.SttProfile = kotlinx.coroutines.runBlocking {
        profiles.getCurrentSttProfile()
    }

    /** 重置单例实例 在需要更改语音服务类型或释放资源时调用 */
    fun resetInstance() {
        runCatching { instance?.shutdown() }
        instance = null
        currentProfileId = null
    }
}
