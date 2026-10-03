/**
 * CloneTTS HTTP 引擎：调用本机/局域网 CloneTTS App 的 HTTP 服务合成并播放。
 *
 * - 接口：GET /api/tts?text={URL编码}&speed={语速×10}[&voice={音色alias}]，
 *   speed 刻度 10=1.0x（与 CloneTTS 文档一致）；不带 voice 时由 CloneTTS
 *   当前默认音色兜底；
 * - 响应为 WAV（实测 PCM 16bit 单声道 24kHz），解析后经 TtsStreamPlayer 播放；
 * - 打断：stop() 置位 + 取消进行中的 HTTP 调用（可立即解除工作线程同步阻塞），
 *   被打断统一按 onDone 收尾；
 * - 引擎无状态（无模型加载），每次播报由编排层按当前配置构造。
 *
 * 线程契约：speak/stop 仅由 TtsPlaybackCoordinator 的单一工作线程调用；
 * stop 可任意线程调用（cancel 线程安全）。
 *
 * 归属模块：tts
 */
package com.brycewg.asrkb.tts

import android.content.Context
import android.util.Log
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

internal class CloneTtsHttpEngine(
    context: Context,
    private val baseUrl: String,
    private val voiceAlias: String
) : TtsEngine {
    companion object {
        private const val TAG = "CloneTtsHttp"

        /** 合成请求连接超时（服务未开时快速失败） */
        private const val CONNECT_TIMEOUT_S = 3L

        /** 合成请求读超时（NanoHTTPD 整段合成后才回包，即首字节等待上限） */
        private const val READ_TIMEOUT_S = 30L

        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
                .build()
        }

        /** 规范化服务地址：补 http:// 前缀、去尾部斜杠；空白回默认同机地址 */
        fun normalizeBaseUrl(raw: String): String {
            var s = raw.trim()
            if (s.isBlank()) return com.brycewg.asrkb.store.Prefs.DEFAULT_TTS_CLONE_TTS_BASE_URL
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
            return s.trimEnd('/')
        }

        /** bibi 语速（0.5-2.0）→ CloneTTS speed 刻度（10=1.0x，即 ×10 取整） */
        fun speedToCloneTts(speed: Float): Int = (speed * 10).roundToInt().coerceIn(5, 20)

        /** 构造 /api/tts 请求地址（text/speed 必带，voice 非空才带） */
        fun buildTtsUrl(baseUrl: String, text: String, speed: Float, voiceAlias: String): String {
            val sb = StringBuilder(normalizeBaseUrl(baseUrl))
                .append("/api/tts?text=")
                .append(urlEncode(text))
                .append("&speed=")
                .append(speedToCloneTts(speed))
            if (voiceAlias.isNotBlank()) {
                sb.append("&voice=").append(urlEncode(voiceAlias))
            }
            return sb.toString()
        }

        private fun urlEncode(value: String): String =
            URLEncoder.encode(value, "UTF-8")

        /**
         * 解析 WAV 为 PCM 样本。仅支持 PCM 16bit（CloneTTS 实测输出格式）；
         * 非 RIFF/非 PCM/位深不符返回 null，由调用方报错（便于日志暴露格式变化）。
         */
        fun parseWav(bytes: ByteArray): PcmWav? {
            if (bytes.size < 44) return null
            if (bytes[0] != 'R'.code.toByte() || bytes[1] != 'I'.code.toByte() ||
                bytes[2] != 'F'.code.toByte() || bytes[3] != 'F'.code.toByte()
            ) {
                return null
            }
            if (bytes[8] != 'W'.code.toByte() || bytes[9] != 'A'.code.toByte() ||
                bytes[10] != 'V'.code.toByte() || bytes[11] != 'E'.code.toByte()
            ) {
                return null
            }
            var offset = 12
            var sampleRate = 0
            var channels = 0
            var bitsPerSample = 0
            var dataOffset = -1
            var dataLength = 0
            // 逐块遍历 RIFF chunk，取 fmt 与 data（容忍 chunk 顺序与额外 chunk）
            while (offset + 8 <= bytes.size) {
                val chunkId = String(bytes, offset, 4, Charsets.US_ASCII)
                val chunkSize = readIntLe(bytes, offset + 4)
                val bodyStart = offset + 8
                when (chunkId) {
                    "fmt " -> {
                        if (bodyStart + 16 > bytes.size) return null
                        val audioFormat = readShortLe(bytes, bodyStart)
                        channels = readShortLe(bytes, bodyStart + 2)
                        sampleRate = readIntLe(bytes, bodyStart + 4)
                        bitsPerSample = readShortLe(bytes, bodyStart + 14)
                        if (audioFormat != 1) return null
                    }

                    "data" -> {
                        dataOffset = bodyStart
                        dataLength = minOf(chunkSize, bytes.size - bodyStart)
                    }
                }
                if (dataOffset >= 0 && sampleRate > 0) break
                // chunk 按字对齐（奇数长度补 1 字节）
                offset = bodyStart + chunkSize + (chunkSize and 1)
            }
            if (dataOffset < 0 || dataLength <= 0) return null
            if (sampleRate <= 0 || bitsPerSample != 16 || channels !in 1..2) return null
            val shorts = ShortArray(dataLength / 2)
            var i = 0
            while (i < shorts.size) {
                val b = dataOffset + i * 2
                if (b + 1 >= bytes.size) break
                shorts[i] = (((bytes[b + 1].toInt() and 0xFF) shl 8) or (bytes[b].toInt() and 0xFF)).toShort()
                i++
            }
            return PcmWav(sampleRate, channels, shorts)
        }

        private fun readShortLe(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

        private fun readIntLe(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    /** 解析后的 PCM 音频 */
    class PcmWav(val sampleRate: Int, val channelCount: Int, val samples: ShortArray)

    private val appContext: Context = context.applicationContext

    @Volatile private var speaking: Boolean = false

    @Volatile private var cancelled: Boolean = false

    @Volatile private var player: TtsStreamPlayer? = null

    @Volatile private var activeCall: okhttp3.Call? = null

    override fun speak(text: String, sid: Int, speed: Float, callback: TtsSpeakCallback) {
        if (speaking) {
            callback.onError("CloneTTS engine is busy")
            return
        }
        speaking = true
        cancelled = false
        try {
            val bytes = fetchAudio(text, speed)
            if (cancelled) {
                callback.onDone()
                return
            }
            val wav = parseWav(bytes)
                ?: throw IllegalStateException("CloneTTS returned unsupported audio (${bytes.size} bytes, expect WAV PCM16)")
            val p = TtsStreamPlayer(appContext, wav.sampleRate, wav.channelCount)
            player = p
            p.start()
            p.write(wav.samples)
            p.drain()
            callback.onDone()
        } catch (t: Throwable) {
            Log.w(TAG, "CloneTTS speak failed", t)
            if (cancelled) {
                // 打断视为完成（与本地离线引擎语义一致）
                callback.onDone()
            } else {
                callback.onError(t.message ?: t.javaClass.simpleName)
            }
        } finally {
            player?.release()
            player = null
            activeCall = null
            speaking = false
        }
    }

    override fun stop() {
        cancelled = true
        try {
            activeCall?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to cancel clone tts call", t)
        }
        player?.cancel()
    }

    override fun isSpeaking(): Boolean = speaking

    /** 同步拉取音频字节；非 2xx 透传服务端错误文本（如音色不存在的 400 提示） */
    private fun fetchAudio(text: String, speed: Float): ByteArray {
        val url = buildTtsUrl(baseUrl, text, speed, voiceAlias)
        val call = client.newCall(Request.Builder().url(url).get().build())
        activeCall = call
        call.execute().use { response ->
            if (!response.isSuccessful) {
                val body = runCatching { response.body?.string() }.getOrNull()?.trim()?.take(200)
                throw IllegalStateException(
                    "HTTP ${response.code}${if (body.isNullOrBlank()) "" else ": $body"}"
                )
            }
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (bytes.isEmpty()) throw IllegalStateException("CloneTTS returned empty body")
            return bytes
        }
    }
}

/**
 * CloneTTS 音色列表（GET /api/voices）拉取与解析。
 *
 * 实测响应：[{"id":"uuid","name":"清雅","alias":"qingya","numSteps":4}]；
 * 解析保持宽容（元素为字符串、缺字段均可兜底），便于服务端字段演进不炸 UI。
 *
 * 归属模块：tts
 */
internal object CloneTtsVoices {
    private const val CONNECT_TIMEOUT_S = 3L
    private const val READ_TIMEOUT_S = 10L

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    data class VoiceInfo(val name: String, val alias: String) {
        val label: String get() = if (name == alias) name else "$name ($alias)"
    }

    /** 拉取音色列表原始 JSON（供缓存与解析）；网络失败抛异常由调用方兜底 */
    fun fetchJson(baseUrl: String): String {
        val url = CloneTtsHttpEngine.normalizeBaseUrl(baseUrl) + "/api/voices"
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            return response.body?.string() ?: throw IllegalStateException("empty body")
        }
    }

    /** 宽容解析：数组元素为对象（name/alias）或纯字符串均可 */
    fun parse(json: String): List<VoiceInfo> = try {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { i ->
            when (val element = array.opt(i)) {
                is String -> element.takeIf { it.isNotBlank() }?.let { VoiceInfo(it, it) }
                is JSONObject -> {
                    val alias = element.optString("alias").ifBlank { element.optString("name") }
                    val name = element.optString("name").ifBlank { alias }
                    alias.takeIf { it.isNotBlank() }?.let { VoiceInfo(name, it) }
                }

                else -> null
            }
        }
    } catch (t: Throwable) {
        Log.w("CloneTtsVoices", "Failed to parse voices json", t)
        emptyList()
    }
}
