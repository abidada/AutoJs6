package com.ai.assistance.operit.api.voice

import android.content.Context
import com.ai.assistance.operit.R

/**
 * HOSTCOMPAT-ADJACENT STUB CATALOGS (Operit port): the upstream cloud/local TTS engine
 * providers were trimmed (runtime TTS goes through hostcompat BibiSpeechBridge per decision
 * C15). These objects keep only the static voice catalogs / default endpoints that the
 * settings screen reads, under the original names, so the settings UI keeps compiling against
 * upstream diffs. Engine logic (HTTP clients, WebSocket streaming, synthesis) is NOT ported.
 * Data below is copied verbatim from the trimmed upstream providers.
 */
object MimoVoiceProvider {
    const val DEFAULT_ENDPOINT_URL = "https://api.xiaomimimo.com/v1/chat/completions"
    const val DEFAULT_MODEL_NAME = "mimo-v2.5-tts"
    const val DEFAULT_VOICE_ID = "mimo_default"

    val AVAILABLE_VOICES =
        listOf(
            VoiceService.Voice(
                id = DEFAULT_VOICE_ID,
                name = "MiMo Default",
                locale = "multi",
                gender = "NEUTRAL"
            ),
            VoiceService.Voice(id = "冰糖", name = "冰糖", locale = "zh-CN", gender = "FEMALE"),
            VoiceService.Voice(id = "茉莉", name = "茉莉", locale = "zh-CN", gender = "FEMALE"),
            VoiceService.Voice(id = "苏打", name = "苏打", locale = "zh-CN", gender = "MALE"),
            VoiceService.Voice(id = "白桦", name = "白桦", locale = "zh-CN", gender = "MALE"),
            VoiceService.Voice(id = "Mia", name = "Mia", locale = "en-US", gender = "FEMALE"),
            VoiceService.Voice(id = "Chloe", name = "Chloe", locale = "en-US", gender = "FEMALE"),
            VoiceService.Voice(id = "Milo", name = "Milo", locale = "en-US", gender = "MALE"),
            VoiceService.Voice(id = "Dean", name = "Dean", locale = "en-US", gender = "MALE")
        )
}

object DoubaoVoiceProvider {
    const val DEFAULT_ENDPOINT_URL = "https://openspeech.bytedance.com/api/v1/tts"
    const val DEFAULT_CLUSTER = "volcano_tts"
    const val DEFAULT_VOICE_ID = "BV700_V2_streaming"

    val AVAILABLE_VOICES =
        listOf(
            VoiceService.Voice(
                id = DEFAULT_VOICE_ID,
                name = DEFAULT_VOICE_ID,
                locale = "zh-CN",
                gender = "NEUTRAL",
            )
        )
}

object OpenAIRealtimeVoiceProvider {
    val AVAILABLE_VOICES = listOf(
        VoiceService.Voice("alloy", "alloy", "en-US", "NEUTRAL"),
        VoiceService.Voice("ash", "ash", "en-US", "NEUTRAL"),
        VoiceService.Voice("ballad", "ballad", "en-US", "NEUTRAL"),
        VoiceService.Voice("cedar", "cedar", "en-US", "NEUTRAL"),
        VoiceService.Voice("coral", "coral", "en-US", "NEUTRAL"),
        VoiceService.Voice("echo", "echo", "en-US", "NEUTRAL"),
        VoiceService.Voice("marin", "marin", "en-US", "NEUTRAL"),
        VoiceService.Voice("sage", "sage", "en-US", "NEUTRAL"),
        VoiceService.Voice("shimmer", "shimmer", "en-US", "NEUTRAL"),
        VoiceService.Voice("verse", "verse", "en-US", "NEUTRAL")
    )
}

object OpenAIVoiceProvider {
    val AVAILABLE_VOICES = listOf(
        VoiceService.Voice("alloy", "alloy", "en-US", "NEUTRAL"),
        VoiceService.Voice("echo", "echo", "en-US", "NEUTRAL"),
        VoiceService.Voice("fable", "fable", "en-US", "NEUTRAL"),
        VoiceService.Voice("onyx", "onyx", "en-US", "NEUTRAL"),
        VoiceService.Voice("nova", "nova", "en-US", "NEUTRAL"),
        VoiceService.Voice("shimmer", "shimmer", "en-US", "NEUTRAL")
    )
}

object SiliconFlowVoiceProvider {
    const val DEFAULT_VOICE_ID = "charles"

    // 可用音色列表 - 根据硅基流动官方文档
    fun getAvailableVoices(context: Context): List<VoiceService.Voice> = listOf(
        VoiceService.Voice("alex", context.getString(R.string.siliconflow_voice_alex), "zh-CN", "MALE"),
        VoiceService.Voice("benjamin", context.getString(R.string.siliconflow_voice_benjamin), "zh-CN", "MALE"),
        VoiceService.Voice("charles", context.getString(R.string.siliconflow_voice_charles), "zh-CN", "MALE"),
        VoiceService.Voice("david", context.getString(R.string.siliconflow_voice_david), "zh-CN", "MALE"),
        VoiceService.Voice("anna", context.getString(R.string.siliconflow_voice_anna), "zh-CN", "FEMALE"),
        VoiceService.Voice("bella", context.getString(R.string.siliconflow_voice_bella), "zh-CN", "FEMALE"),
        VoiceService.Voice("claire", context.getString(R.string.siliconflow_voice_claire), "zh-CN", "FEMALE"),
        VoiceService.Voice("diana", context.getString(R.string.siliconflow_voice_diana), "zh-CN", "FEMALE")
    )
}
