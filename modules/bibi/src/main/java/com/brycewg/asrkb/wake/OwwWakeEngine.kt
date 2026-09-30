/**
 * OpenWakeWord 唤醒引擎（重写自 openWakeWord Apache-2.0 的流式推理管线，未复制任何代码）。
 *
 * 管线（与 openWakeWord 官方 Python 实现逐块对齐）：
 * 1. 每 1280 样本（80ms @16kHz）一个处理块；
 * 2. 取最近 1280+480=1760 样本（float32，int16 原值）跑 melspectrogram 模型 → 8 帧 × 32 mel bins，
 *    变换 spec/10+2 后追加进 mel 缓冲（上限 970 帧）；
 * 3. 取最近 76×32 mel 窗口跑 embedding 模型 → 1 帧 96 维语音特征，追加进特征缓冲（上限 120 帧）；
 * 4. 取最近 16×96 特征窗口跑 wake 模型 → 单值概率；概率 ≥ 阈值判定命中，启动侧做 4s 防抖。
 *
 * 归属模块：wake
 */
package com.brycewg.asrkb.wake

import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer

internal class OwwWakeEngine(
    melspecModel: ByteBuffer,
    embeddingModel: ByteBuffer,
    wakeModel: ByteBuffer
) : AutoCloseable {

    companion object {
        private const val TAG = "OwwWakeEngine"

        const val SAMPLE_RATE = 16000
        const val CHUNK_SAMPLES = 1280
        const val MEL_CONTEXT_EXTRA = 480
        const val MEL_FRAMES_PER_CHUNK = 8
        const val MEL_WINDOW = 76
        const val MEL_BINS = 32
        const val MEL_BUFFER_MAX = 970
        const val FEATURE_DIM = 96
        const val FEATURE_WINDOW = 16
        const val FEATURE_BUFFER_MAX = 120
        const val WARMUP_CHUNKS = 24

        /** 与 openWakeWord 一致：模型初始化后的前 5 帧预测视为无效。 */
        private const val INIT_INVALID_FRAMES = 5
    }

    private val melspec = Interpreter(melspecModel, Interpreter.Options().setNumThreads(1)).also { it.allocateTensors() }
    private val embed = Interpreter(embeddingModel, Interpreter.Options().setNumThreads(2)).also { it.allocateTensors() }
    private val wake = Interpreter(wakeModel, Interpreter.Options().setNumThreads(1)).also { it.allocateTensors() }

    private val rawHistory = ArrayDeque<Float>(CHUNK_SAMPLES + MEL_CONTEXT_EXTRA)
    private val melBuffer = ArrayDeque<FloatArray>(MEL_BUFFER_MAX)
    private val featureBuffer = ArrayDeque<FloatArray>(FEATURE_BUFFER_MAX)

    private var chunkCounter = 0

    init {
        repeat(MEL_WINDOW) { melBuffer.addLast(FloatArray(MEL_BINS) { 1f }) }
        // 用静音预热：填满特征缓冲并跳过初始化期无效预测
        val silence = ShortArray(CHUNK_SAMPLES)
        repeat(WARMUP_CHUNKS) { process(silence) }
        chunkCounter = 0
    }

    /** 重置内部状态（识别会话结束后恢复监听前调用），并重新预热。 */
    fun reset() {
        rawHistory.clear()
        melBuffer.clear()
        featureBuffer.clear()
        repeat(MEL_WINDOW) { melBuffer.addLast(FloatArray(MEL_BINS) { 1f }) }
        val silence = ShortArray(CHUNK_SAMPLES)
        repeat(WARMUP_CHUNKS) { process(silence) }
        chunkCounter = 0
    }

    /**
     * 处理一个 1280 样本（80ms）的 PCM16 块。
     * @return 唤醒概率（初始化预热期恒返回 0f）
     */
    fun process(samples: ShortArray): Float {
        for (s in samples) rawHistory.addLast(s.toFloat())
        while (rawHistory.size > CHUNK_SAMPLES + MEL_CONTEXT_EXTRA) rawHistory.removeFirst()

        // 1) melspectrogram：最近 1760 样本 → 8 帧 × 32 bins
        val ctx = FloatArray(CHUNK_SAMPLES + MEL_CONTEXT_EXTRA)
        var i = 0
        for (v in rawHistory) ctx[i++] = v
        val melShape = melspec.getInputTensor(0).shape()
        require(melShape.size == 2 && melShape[0] == 1 && (melShape[1] == -1 || melShape[1] == ctx.size)) {
            "Unexpected melspectrogram input shape ${melShape.contentToString()}"
        }
        val melOut = runWithDynamicOutput(melspec, arrayOf(ctx))

        // 变换 spec/10 + 2 后按帧追加
        val flat = FloatArray(melOut.second.reduce { a, b -> a * b })
        flattenFloats(melOut.first, flat, intArrayOf(0))
        val frames = flat.size / MEL_BINS
        for (f in 0 until frames.coerceAtMost(MEL_FRAMES_PER_CHUNK)) {
            val frame = FloatArray(MEL_BINS)
            for (b in 0 until MEL_BINS) {
                frame[b] = flat[f * MEL_BINS + b] / 10f + 2f
            }
            melBuffer.addLast(frame)
            if (melBuffer.size > MEL_BUFFER_MAX) melBuffer.removeFirst()
        }

        // 2) embedding：最近 76×32 mel 窗口 → 1 帧 × 96 维
        val embedIn = Array(1) {
            Array(MEL_WINDOW) { Array(MEL_BINS) { FloatArray(1) } }
        }
        var w = 0
        for (frame in melBuffer.toList().takeLast(MEL_WINDOW)) {
            if (w >= MEL_WINDOW) break
            for (b in 0 until MEL_BINS) embedIn[0][w][b][0] = frame[b]
            w++
        }
        val embedOut = runWithDynamicOutput(embed, embedIn)
        val embFlat = FloatArray(embedOut.second.reduce { a, b -> a * b })
        flattenFloats(embedOut.first, embFlat, intArrayOf(0))
        val feature = if (embFlat.size >= FEATURE_DIM) {
            embFlat.copyOfRange(embFlat.size - FEATURE_DIM, embFlat.size)
        } else {
            embFlat
        }
        featureBuffer.addLast(feature)
        if (featureBuffer.size > FEATURE_BUFFER_MAX) featureBuffer.removeFirst()

        // 3) wake：最近 16×96 特征窗口 → 概率
        chunkCounter++
        if (chunkCounter <= INIT_INVALID_FRAMES) return 0f
        val featWindow = Array(FEATURE_WINDOW) { FloatArray(FEATURE_DIM) }
        var fw = 0
        for (feat in featureBuffer.toList().takeLast(FEATURE_WINDOW)) {
            if (fw >= FEATURE_WINDOW) break
            featWindow[fw++] = feat
        }
        val wakeIn = arrayOf(featWindow)
        val wakeOut = runWithDynamicOutput(wake, wakeIn)
        val probFlat = FloatArray(wakeOut.second.reduce { a, b -> a * b }.coerceAtLeast(1))
        flattenFloats(wakeOut.first, probFlat, intArrayOf(0))
        return probFlat.lastOrNull() ?: 0f
    }

    private fun runWithDynamicOutput(interp: Interpreter, input: Any): Pair<Any, IntArray> {
        val shape = interp.getOutputTensor(0).shape()
        val output = buildNestedFloatArray(shape, 0)
        interp.run(input, output)
        return output to shape
    }

    private fun buildNestedFloatArray(shape: IntArray, dim: Int): Any =
        if (dim == shape.size - 1) {
            FloatArray(shape[dim])
        } else {
            Array(shape[dim]) { buildNestedFloatArray(shape, dim + 1) }
        }

    private fun flattenFloats(node: Any, out: FloatArray, cursor: IntArray) {
        if (node is FloatArray) {
            for (v in node) {
                if (cursor[0] < out.size) out[cursor[0]++] = v
            }
        } else if (node is Array<*>) {
            for (child in node) {
                if (child != null) flattenFloats(child, out, cursor)
            }
        }
    }

    override fun close() {
        try {
            melspec.close()
        } catch (_: Throwable) {
        }
        try {
            embed.close()
        } catch (_: Throwable) {
        }
        try {
            wake.close()
        } catch (_: Throwable) {
        }
        Log.d(TAG, "engine closed")
    }
}
