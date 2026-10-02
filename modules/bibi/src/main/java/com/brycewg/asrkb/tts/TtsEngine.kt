/**
 * TTS 引擎抽象：speak/stop/isSpeaking。
 *
 * 引擎只负责「合成 + 播放 + 打断」，不感知队列与场景策略（那是
 * TtsPlaybackCoordinator 的职责）；在线 TTS 未来实现同一接口，接入即用。
 *
 * 线程契约：speak/stop 由 TtsPlaybackCoordinator 的单一工作线程串行调用。
 *
 * 归属模块：tts
 */
package com.brycewg.asrkb.tts

interface TtsSpeakCallback {
    /** 播报正常结束或被打断（区分意义不大，统一走 onDone） */
    fun onDone()

    /** 引擎错误（加载失败/合成异常等） */
    fun onError(message: String)
}

interface TtsEngine {
    /** 播报一段文本；重复调用前必须先 [stop] */
    fun speak(text: String, speed: Float, callback: TtsSpeakCallback)

    /** 打断当前播报（若在播）；幂等 */
    fun stop()

    fun isSpeaking(): Boolean
}
