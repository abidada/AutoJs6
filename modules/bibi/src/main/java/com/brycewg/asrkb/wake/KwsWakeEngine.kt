/**
 * 语音唤醒引擎：封装 sherpa-onnx KeywordSpotter（zipformer KWS，wenetspeech 中文模型）。
 *
 * - 模型 int8 三件套 + tokens/keywords 由 assets 直装（约 5MB）；
 * - 预置 8 个中文唤醒词（小爱同学/你好问问/小艺小艺/小米小米/你好军哥/蛋哥蛋哥/林美丽/你好西西），
 *   [keywords] 传入行会与默认词表合并（sherpa CreateStream 行为，非替换）；单选某词时由
 *   WakeWordService 在命中处按词名过滤；
 * - 喂流方式与官方 SherpaOnnxKws 示例一致：100ms 块 acceptWaveform → isReady→decode→getResult→reset。
 *
 * 归属模块：wake
 */
package com.brycewg.asrkb.wake

import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

internal class KwsWakeEngine(
    assetManager: AssetManager,
    private val keywords: String? = null
) : AutoCloseable {

    companion object {
        private const val TAG = "KwsWakeEngine"
        private const val MODEL_DIR = "kws/wenetspeech-3.3M"
        const val SAMPLE_RATE = 16000
    }

    private val kws = KeywordSpotter(
        assetManager = assetManager,
        config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$MODEL_DIR/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    decoder = "$MODEL_DIR/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    joiner = "$MODEL_DIR/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
                ),
                tokens = "$MODEL_DIR/tokens.txt",
                numThreads = 2,
                modelType = "zipformer2",
                provider = "cpu"
            ),
            keywordsFile = "$MODEL_DIR/keywords.txt"
        )
    )

    private var stream: OnlineStream = newStream()

    private fun newStream(): OnlineStream = if (keywords.isNullOrBlank()) {
        kws.createStream()
    } else {
        kws.createStream(keywords)
    }

    /** 喂入一段 16kHz 单声道 PCM（浮点 [-1,1)）。 */
    fun acceptWaveform(samples: FloatArray) {
        stream.acceptWaveform(samples, SAMPLE_RATE)
    }

    /**
     * 解码当前流。
     * @return 命中的唤醒词显示名（如「小爱同学」）；无命中返回 null。命中后内部已 reset 防重复。
     */
    fun pollKeyword(): String? {
        var hit: String? = null
        while (kws.isReady(stream)) {
            kws.decode(stream)
            val text = kws.getResult(stream).keyword
            if (text.isNotBlank()) {
                hit = text
                // 官方示例要求：命中后立刻 reset 流
                kws.reset(stream)
            }
        }
        return hit
    }

    /** 丢弃当前流状态（让出麦克风给识别会话后恢复监听时调用）。 */
    fun recreateStream() {
        try {
            stream.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to release stream", t)
        }
        stream = newStream()
    }

    override fun close() {
        try {
            stream.release()
        } catch (_: Throwable) {
        }
        try {
            kws.release()
        } catch (_: Throwable) {
        }
        Log.d(TAG, "engine closed")
    }
}
