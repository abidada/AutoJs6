/**
 * sherpa-onnx OfflineTts 反射封装：合成 + AudioTrack 播放 + 可打断。
 *
 * - 与 asr 包同构，全部走反射调用 com.k2fsa.sherpa.onnx（软依赖，缺类不崩溃）；
 * - 优先 generateWithCallback 边合成边播（首字延迟最低），签名不可用时退回
 *   generate 整段生成后播放；
 * - 播放/焦点/打断统一委托 TtsStreamPlayer（与 CloneTtsHttpEngine 共用）；
 * - 打断：stop() 置位后回调返回 0，JNI 侧停止生成并返回已完成的部分音频。
 *
 * 线程契约：speak/stop 仅由 TtsPlaybackCoordinator 的单一工作线程调用。
 *
 * 归属模块：tts
 */
package com.brycewg.asrkb.tts

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import kotlin.jvm.functions.Function1

internal class OfflineSherpaTtsEngine private constructor(
    private val context: Context,
    private val ttsInstance: Any,
    private val ttsClass: Class<*>
) : TtsEngine {
    companion object {
        private const val TAG = "OfflineSherpaTts"

        /** 预热合成文本（只合成不播放，取最短常用音节即可） */
        private const val WARMUP_TEXT = "好"

        @Volatile private var classLoadFailed: Boolean = false

        fun create(
            context: Context,
            modelFiles: TtsLocalModelCatalog.ModelFiles,
            family: TtsLocalModelCatalog.TtsModelFamily,
            numThreads: Int
        ): OfflineSherpaTtsEngine? {
            if (classLoadFailed) return null
            return try {
                try {
                    System.loadLibrary("sherpa-onnx-jni")
                } catch (t: Throwable) {
                    // 重复加载会抛，已被 AAR 内 companion 处理；这里仅兜底记录
                    Log.d(TAG, "loadLibrary sherpa-onnx-jni: ${t.message}")
                }

                val ttsClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineTts")
                val configClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsConfig")
                val modelConfigClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsModelConfig")
                val vitsClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig")

                val modelConfig = modelConfigClass.getDeclaredConstructor().newInstance()
                setField(modelConfig, "numThreads", numThreads)
                setField(modelConfig, "debug", false)
                setField(modelConfig, "provider", "cpu")

                when (family) {
                    TtsLocalModelCatalog.TtsModelFamily.KOKORO -> {
                        // kokoro-multi-lang：中英混合（中文词库 + espeak-ng 兜底）
                        // 多语种模型 lexicon/lang 双缺时 native 会 EXIT(-1) 杀进程，先拦截
                        val lexiconArg = modelFiles.lexiconArg()
                        if (lexiconArg.isBlank()) {
                            Log.e(TAG, "Kokoro model missing lexicon*.txt; refuse to create engine")
                            return null
                        }
                        val kokoroClass = Class.forName(
                            "com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig"
                        )
                        val kokoro = kokoroClass.getDeclaredConstructor().newInstance()
                        setField(kokoro, "model", modelFiles.onnxFile.absolutePath)
                        modelFiles.voicesBinFile?.let {
                            setField(kokoro, "voices", it.absolutePath)
                        }
                        setField(kokoro, "tokens", modelFiles.tokensFile.absolutePath)
                        modelFiles.espeakDataDir?.let {
                            setField(kokoro, "dataDir", it.absolutePath)
                        }
                        // 词库支持逗号分隔多文件（lexicon-zh.txt 等，见 kokoro-multi-lang-lexicon.cc）
                        setField(kokoro, "lexicon", lexiconArg)
                        setField(modelConfig, "kokoro", kokoro)
                    }

                    TtsLocalModelCatalog.TtsModelFamily.VITS -> {
                        val vits = vitsClass.getDeclaredConstructor().newInstance()
                        setField(vits, "model", modelFiles.onnxFile.absolutePath)
                        setField(vits, "tokens", modelFiles.tokensFile.absolutePath)
                        // 打包布局差异：lexicon 布局（xiao_ya/chaowen/melo）走 lexicon.txt；
                        // espeak 布局（huayan 等旧 piper zh 包）走 dataDir（+ 可选 dictDir jieba）
                        if (modelFiles.lexiconFile != null) {
                            setField(vits, "lexicon", modelFiles.lexiconFile.absolutePath)
                        }
                        if (modelFiles.espeakDataDir != null) {
                            setField(vits, "dataDir", modelFiles.espeakDataDir.absolutePath)
                        }
                        if (modelFiles.dictDir != null) {
                            setField(vits, "dictDir", modelFiles.dictDir.absolutePath)
                        }
                        setField(modelConfig, "vits", vits)
                    }
                }

                val config = configClass.getDeclaredConstructor().newInstance()
                setField(config, "model", modelConfig)
                // 文本规范化规则 fst（xiao_ya 的 phone/number/date），逗号分隔
                val ruleFstsArg = modelFiles.ruleFstsArg()
                if (ruleFstsArg.isNotBlank()) {
                    setField(config, "ruleFsts", ruleFstsArg)
                }

                val ctor = ttsClass.getDeclaredConstructor(
                    AssetManager::class.java,
                    configClass
                )
                // assetManager = null：全部路径按文件系统绝对路径解释（模型在应用目录，不进 APK）
                val instance = ctor.newInstance(null, config)
                OfflineSherpaTtsEngine(context.applicationContext, instance, ttsClass)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to create OfflineTts", t)
                classLoadFailed = true
                null
            }
        }

        private fun setField(target: Any, name: String, value: Any?): Boolean = try {
            val field = target.javaClass.getDeclaredField(name)
            field.isAccessible = true
            field.set(target, value)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to set field $name", t)
            false
        }
    }

    @Volatile private var speaking: Boolean = false

    @Volatile private var cancelled: Boolean = false

    @Volatile private var player: TtsStreamPlayer? = null

    val sampleRate: Int = try {
        (ttsClass.getMethod("sampleRate").invoke(ttsInstance) as? Int) ?: 22050
    } catch (t: Throwable) {
        Log.w(TAG, "Failed to read tts sampleRate", t)
        22050
    }

    override fun speak(text: String, sid: Int, speed: Float, callback: TtsSpeakCallback) {
        if (speaking) {
            callback.onError("TTS engine is busy")
            return
        }
        speaking = true
        cancelled = false
        val clampedSpeed = speed.coerceIn(0.5f, 2.0f)
        try {
            val p = TtsStreamPlayer(context, sampleRate)
            player = p
            p.start()
            try {
                generateStreaming(text, sid, clampedSpeed, p)
            } catch (streamingFailure: Throwable) {
                Log.w(TAG, "generateWithCallback unavailable, fallback to generate", streamingFailure)
                generateAll(text, sid, clampedSpeed, p)
            }
            p.drain()
            callback.onDone()
        } catch (t: Throwable) {
            Log.e(TAG, "TTS speak failed", t)
            callback.onError(t.message ?: t.javaClass.simpleName)
        } finally {
            player?.release()
            player = null
            speaking = false
        }
    }

    override fun stop() {
        cancelled = true
        player?.cancel()
    }

    /**
     * 静默预热：仅合成、不创建 AudioTrack、不申请音频焦点——
     * 触发 espeak-ng/jieba/声学模型初始化，消除首次播报延迟。
     * 期间置位 speaking，避免与真实播报并发进入 native 合成。
     */
    fun warmUp() {
        if (speaking) return
        speaking = true
        cancelled = false
        try {
            val method = ttsClass.getMethod(
                "generate",
                String::class.java,
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType
            )
            // 样本直接丢弃：预热只关心初始化副作用
            method.invoke(ttsInstance, WARMUP_TEXT, 0, 1.0f)
        } catch (t: Throwable) {
            Log.w(TAG, "TTS warm-up synthesis failed", t)
        } finally {
            speaking = false
        }
    }

    override fun isSpeaking(): Boolean = speaking

    fun release() {
        stop()
        try {
            // 1.13.4 Kotlin API 提供 release()（内部即 native delete）
            val releaseMethod = try {
                ttsClass.getMethod("release")
            } catch (_: Throwable) {
                ttsClass.getMethod("free")
            }
            releaseMethod.invoke(ttsInstance)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to release OfflineTts", t)
        }
    }

    // ==================== 合成 ====================

    /**
     * 流式合成：回调经共享播放器写入 AudioTrack；返回 0 停止生成。
     * @return 是否成功发起并完成合成（打断视为完成）
     */
    private fun generateStreaming(text: String, sid: Int, speed: Float, p: TtsStreamPlayer): Boolean {
        val callback = object : Function1<FloatArray, Int> {
            override fun invoke(chunk: FloatArray): Int {
                if (cancelled) return 0
                if (chunk.isNotEmpty()) p.write(toPcm16(chunk))
                return 1
            }
        }
        val method = ttsClass.getMethod(
            "generateWithCallback",
            String::class.java,
            Int::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Function1::class.java
        )
        method.invoke(ttsInstance, text, sid, speed, callback)
        return true
    }

    /** 整段生成后播放（generateWithCallback 不可用时的兜底路径） */
    private fun generateAll(text: String, sid: Int, speed: Float, p: TtsStreamPlayer): Boolean {
        val method = ttsClass.getMethod(
            "generate",
            String::class.java,
            Int::class.javaPrimitiveType,
            Float::class.javaPrimitiveType
        )
        val audio = method.invoke(ttsInstance, text, sid, speed) ?: return false
        val samples = audio.javaClass.getMethod("getSamples").invoke(audio) as? FloatArray
        if (samples == null || samples.isEmpty()) return false
        if (!cancelled) p.write(toPcm16(samples))
        return true
    }

    /** [-1,1] 浮点样本 → 16bit PCM 样本 */
    private fun toPcm16(chunk: FloatArray): ShortArray {
        val shorts = ShortArray(chunk.size)
        var i = 0
        while (i < chunk.size) {
            val f = chunk[i].coerceIn(-1f, 1f)
            shorts[i] = (f * 32767f).toInt().toShort()
            i++
        }
        return shorts
    }
}
