/**
 * sherpa-onnx OfflineTts 反射封装：合成 + AudioTrack 播放 + 可打断。
 *
 * - 与 asr 包同构，全部走反射调用 com.k2fsa.sherpa.onnx（软依赖，缺类不崩溃）；
 * - 优先 generateWithCallback 边合成边播（首字延迟最低），签名不可用时退回
 *   generate 整段生成后播放；
 * - 音频焦点：播报前申请瞬时 MAY_DUCK（压低媒体音），结束即释放；
 * - 打断：stop() 置位后回调返回 0，JNI 侧停止生成并返回已完成的部分音频。
 *
 * 线程契约：speak/stop 仅由 TtsPlaybackCoordinator 的单一工作线程调用。
 *
 * 归属模块：tts
 */
package com.brycewg.asrkb.tts

import android.content.Context
import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import java.io.File
import kotlin.jvm.functions.Function1

internal class OfflineSherpaTtsEngine private constructor(
    private val context: Context,
    private val ttsInstance: Any,
    private val ttsClass: Class<*>
) : TtsEngine {
    companion object {
        private const val TAG = "OfflineSherpaTts"

        /** 播完等待上限（正常短播报几秒内结束；超时兜底防止工作线程卡死） */
        private const val DRAIN_TIMEOUT_MS = 30_000

        /** 播放头连续无进展的判定轮数（6 × 50ms = 300ms 无进展即视为播完） */
        private const val DRAIN_STAGNANT_POLLS = 6

        /** 写入停滞保护上限（缓冲满且系统侧长时间不消费时抛错，防工作线程卡死） */
        private const val WRITE_STALL_TIMEOUT_MS = 5_000L

        /** 预热合成文本（只合成不播放，取最短常用音节即可） */
        private const val WARMUP_TEXT = "好"

        @Volatile private var classLoadFailed: Boolean = false

        fun create(context: Context, modelFiles: TtsLocalModelCatalog.ModelFiles, numThreads: Int): OfflineSherpaTtsEngine? {
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

                val vits = vitsClass.getDeclaredConstructor().newInstance()
                setField(vits, "model", modelFiles.onnxFile.absolutePath)
                setField(vits, "tokens", modelFiles.tokensFile.absolutePath)
                // 打包布局差异：lexicon 布局（xiao_ya）走 lexicon.txt；
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

                val modelConfig = modelConfigClass.getDeclaredConstructor().newInstance()
                setField(modelConfig, "vits", vits)
                setField(modelConfig, "numThreads", numThreads)
                setField(modelConfig, "debug", false)
                setField(modelConfig, "provider", "cpu")

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

    private val audioManager: AudioManager? =
        context.getSystemService(AudioManager::class.java)

    private val audioAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    @Volatile private var speaking: Boolean = false

    @Volatile private var cancelled: Boolean = false

    @Volatile private var track: AudioTrack? = null

    @Volatile private var focusRequest: AudioFocusRequest? = null

    /** 本轮已写入 AudioTrack 的帧数（用于播完等待，见 [drainTrack]） */
    @Volatile private var writtenFrames: Long = 0L

    val sampleRate: Int = try {
        (ttsClass.getMethod("sampleRate").invoke(ttsInstance) as? Int) ?: 22050
    } catch (t: Throwable) {
        Log.w(TAG, "Failed to read tts sampleRate", t)
        22050
    }

    override fun speak(text: String, speed: Float, callback: TtsSpeakCallback) {
        if (speaking) {
            callback.onError("TTS engine is busy")
            return
        }
        speaking = true
        cancelled = false
        writtenFrames = 0L
        val clampedSpeed = speed.coerceIn(0.5f, 2.0f)
        var acquiredFocus = false
        try {
            acquiredFocus = requestFocus()
            val t = buildTrack()
            track = t
            t.play()
            try {
                generateStreaming(text, clampedSpeed)
            } catch (streamingFailure: Throwable) {
                Log.w(TAG, "generateWithCallback unavailable, fallback to generate", streamingFailure)
                generateAll(text, clampedSpeed)
            }
            drainTrack(t)
            callback.onDone()
        } catch (t: Throwable) {
            Log.e(TAG, "TTS speak failed", t)
            callback.onError(t.message ?: t.javaClass.simpleName)
        } finally {
            releaseTrack()
            if (acquiredFocus) abandonFocus()
            speaking = false
        }
    }

    override fun stop() {
        cancelled = true
        try {
            track?.pause()
            track?.flush()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to stop audio track", t)
        }
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
     * 流式合成：回调写入 AudioTrack；返回 0 停止生成。
     * @return 是否成功发起并完成合成（打断视为完成）
     */
    private fun generateStreaming(text: String, speed: Float): Boolean {
        val t = track ?: throw IllegalStateException("AudioTrack not ready")
        val callback = object : Function1<FloatArray, Int> {
            override fun invoke(chunk: FloatArray): Int {
                if (cancelled) return 0
                if (chunk.isNotEmpty()) writeChunk(t, chunk)
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
        method.invoke(ttsInstance, text, 0, speed, callback)
        return true
    }

    /** 整段生成后播放（generateWithCallback 不可用时的兜底路径） */
    private fun generateAll(text: String, speed: Float): Boolean {
        val t = track ?: throw IllegalStateException("AudioTrack not ready")
        val method = ttsClass.getMethod(
            "generate",
            String::class.java,
            Int::class.javaPrimitiveType,
            Float::class.javaPrimitiveType
        )
        val audio = method.invoke(ttsInstance, text, 0, speed) ?: return false
        val samples = audio.javaClass.getMethod("getSamples").invoke(audio) as? FloatArray
        if (samples == null || samples.isEmpty()) return false
        if (!cancelled) writeChunk(t, samples)
        return true
    }

    // ==================== 播放 ====================

    private fun buildTrack(): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        return AudioTrack.Builder()
            .setAudioAttributes(audioAttrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuffer * 2, 8192))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun writeChunk(t: AudioTrack, chunk: FloatArray) {
        val shorts = ShortArray(chunk.size)
        var i = 0
        while (i < chunk.size) {
            val f = chunk[i].coerceIn(-1f, 1f)
            shorts[i] = (f * 32767f).toInt().toShort()
            i++
        }
        writtenFrames += shorts.size
        var offset = 0
        // 非阻塞写入 + 轮询：WRITE_BLOCKING 在 stop() 暂停轨道后会永久阻塞，
        // 非阻塞写配合 cancelled 检查保证打断能立刻从写循环里退出
        var stallMs = 0L
        while (offset < shorts.size && !cancelled) {
            val written = t.write(shorts, offset, shorts.size - offset, AudioTrack.WRITE_NON_BLOCKING)
            if (written < 0) throw IllegalStateException("AudioTrack write failed: $written")
            if (written == 0) {
                // 缓冲满且长时间无进展（轨道被系统暂停/路由异常）：防永久卡死
                stallMs += 10L
                if (stallMs > WRITE_STALL_TIMEOUT_MS) {
                    throw IllegalStateException("AudioTrack write stalled (no drain in ${WRITE_STALL_TIMEOUT_MS}ms)")
                }
                try {
                    Thread.sleep(10)
                } catch (_: InterruptedException) {
                    break
                }
                continue
            }
            stallMs = 0L
            offset += written
        }
    }

    /**
     * 等待缓冲播完再返回：stop() 后 playbackHeadPosition 追平写入帧数即播完。
     * onDone 必须晚于声音结束，否则开麦闸口会在尾音期间放行重开麦克风。
     *
     * 注意：部分机型 stop() 后会直接丢弃剩余缓冲，播放头停在写入总数之下不再推进；
     * 因此除总时长兜底外，还以「播放头连续 ~300ms 无进展」作为播完判据，
     * 否则每次播报都会白等满 DRAIN_TIMEOUT_MS。
     */
    private fun drainTrack(t: AudioTrack) {
        val total = writtenFrames
        if (total <= 0L) return
        try {
            t.stop()
        } catch (t2: Throwable) {
            Log.w(TAG, "Failed to drain audio track", t2)
            return
        }
        var lastHead = -1L
        var stagnantPolls = 0
        var waited = 0
        while (!cancelled && waited < DRAIN_TIMEOUT_MS) {
            val head = try {
                t.playbackHeadPosition.toLong() and 0xffffffffL
            } catch (_: Throwable) {
                break
            }
            if (head >= total) break
            stagnantPolls = if (head == lastHead) stagnantPolls + 1 else 0
            if (stagnantPolls >= DRAIN_STAGNANT_POLLS) {
                Log.d(TAG, "Drain finished by head stall (head=$head total=$total)")
                break
            }
            lastHead = head
            try {
                Thread.sleep(50)
                waited += 50
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun releaseTrack() {
        val t = track
        track = null
        if (t == null) return
        try {
            t.stop()
        } catch (_: Throwable) {
        }
        try {
            t.release()
        } catch (t2: Throwable) {
            Log.w(TAG, "Failed to release audio track", t2)
        }
    }

    // ==================== 音频焦点 ====================

    private fun requestFocus(): Boolean = try {
        val am = audioManager ?: return true
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
                .setAudioAttributes(audioAttrs)
                .build()
            focusRequest = request
            am.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
        granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    } catch (t: Throwable) {
        Log.w(TAG, "Failed to request tts audio focus", t)
        false
    }

    private fun abandonFocus() {
        try {
            val am = audioManager ?: return
            val request = focusRequest
            if (request != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                am.abandonAudioFocusRequest(request)
                focusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to abandon tts audio focus", t)
        }
    }
}
