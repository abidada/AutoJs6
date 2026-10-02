/**
 * 使用百度短语音识别标准版 REST API 的非流式 ASR 引擎。
 * API 文档：https://cloud.baidu.com/doc/SPEECH/s/Jlbxdezuf
 * 音频限制：16k/8k 采样、16bit 单声道、单次不超过 60 秒（由渐进分段窗口保证）。
 *
 * 归属模块：asr
 */
package com.brycewg.asrkb.asr

import android.content.Context
import android.util.Log
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class BaiduFileAsrEngine(
    context: Context,
    scope: CoroutineScope,
    prefs: Prefs,
    listener: StreamingAsrEngine.Listener,
    onRequestDuration: ((Long) -> Unit)? = null,
    httpClient: OkHttpClient? = null
) : BaseFileAsrEngine(context, scope, prefs, listener, onRequestDuration),
    PcmBatchRecognizer {

    companion object {
        private const val TAG = "BaiduFileAsrEngine"
        internal const val ENDPOINT = "https://vop.baidu.com/server_api"

        // 1537：普通话近场模型（默认，带标点）；粤语 1637 / 四川话 1837 / 英语 1737
        internal const val DEV_PID = 1537
    }

    override val progressiveVendor: AsrVendor? = AsrVendor.Baidu

    private val tokenManager = BaiduAsrTokenManager(
        httpClient = httpClient ?: AsrHttpClientProvider.newBuilder().build()
    )

    private val http: OkHttpClient = httpClient ?: AsrHttpClientProvider.newBuilder()
        .addInterceptor(ApiLogInterceptor())
        .connectTimeout(15, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun ensureReady(): Boolean {
        if (!super.ensureReady()) return false
        if (prefs.baiduApiKey.isBlank() || prefs.baiduSecretKey.isBlank()) {
            listener.onError(context.getString(R.string.error_missing_baidu_keys))
            return false
        }
        return true
    }

    override suspend fun recognize(pcm: ByteArray) {
        try {
            val token = tokenManager.fetchToken(prefs.baiduApiKey, prefs.baiduSecretKey)
            if (token.isNullOrBlank()) {
                listener.onError(context.getString(R.string.error_baidu_token_failed))
                return
            }

            val url = "$ENDPOINT?dev_pid=$DEV_PID&cuid=${resolveCuid()}&token=$token"
            val request = Request.Builder()
                .url(url)
                .tag(
                    ApiLogMeta::class.java,
                    ApiLogRecorder.meta(
                        category = "ASR",
                        vendor = "baidu",
                        model = DEV_PID.toString(),
                        requestStructure = "raw body=audio/pcm;rate=16000, query params=dev_pid, cuid, token"
                    )
                )
                .post(pcm.toRequestBody("audio/pcm;rate=16000".toMediaType()))
                .build()

            val t0 = System.nanoTime()
            val resp = http.newCall(request).execute()
            resp.use { r ->
                val bodyStr = r.body.string().orEmpty()
                val text = parseBaiduAsrText(bodyStr)
                val errorCode = parseBaiduAsrErrorCode(bodyStr)
                if (r.isSuccessful && errorCode == 0) {
                    if (text.isNotBlank()) {
                        val dt = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
                        try {
                            onRequestDuration?.invoke(dt)
                        } catch (_: Throwable) {}
                        listener.onFinal(text)
                    } else {
                        listener.onError(context.getString(R.string.error_asr_empty_result))
                    }
                } else {
                    listener.onError(mapBaiduAsrError(context, errorCode, bodyStr, r.code))
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Baidu recognize failed", t)
            listener.onError(
                context.getString(R.string.error_recognize_failed_with_reason, t.message ?: "")
            )
        }
    }

    override suspend fun recognizeFromPcm(pcm: ByteArray) {
        recognize(pcm)
    }

    private fun resolveCuid(): String {
        val existing = prefs.baiduCuid
        if (existing.isNotBlank()) return existing
        val next = UUID.randomUUID().toString().replace("-", "")
        prefs.baiduCuid = next
        return next
    }
}

/**
 * 从百度 REST 响应中解析 err_no；解析失败（非 JSON 响应）返回 -1。
 */
internal fun parseBaiduAsrErrorCode(body: String): Int = try {
    JSONObject(body).optInt("err_no", -1)
} catch (_: Throwable) {
    -1
}

/**
 * 从百度 REST 响应中解析识别文本：{"err_no":0,"result":["北京天气"]}。
 */
internal fun parseBaiduAsrText(body: String): String = try {
    val obj = JSONObject(body)
    val result = obj.optJSONArray("result")
    if (result != null && result.length() > 0) {
        result.optString(0).trim()
    } else {
        ""
    }
} catch (_: Throwable) {
    ""
}

/**
 * 按百度错误码汇总（https://cloud.baidu.com/doc/SPEECH/s/Zlbxew2qk）映射为用户可读文案。
 */
internal fun mapBaiduAsrError(context: Context, errorCode: Int, body: String, httpCode: Int): String {
    if (errorCode == -1) {
        return context.getString(R.string.error_request_failed_http, httpCode, body.take(200))
    }
    val resId = when (errorCode) {
        3301 -> R.string.error_baidu_audio_quality
        3302 -> R.string.error_baidu_auth_failed
        3303, 3307, 3313, 3315 -> R.string.error_baidu_server_busy
        3304 -> R.string.error_baidu_qps_limit
        3305 -> R.string.error_baidu_daily_limit
        3308 -> R.string.error_baidu_audio_too_long
        3314 -> R.string.error_baidu_audio_too_short
        3309, 3310, 3311, 3312, 3316 -> R.string.error_baidu_audio_invalid
        else -> R.string.error_baidu_api_failed
    }
    if (resId == R.string.error_baidu_api_failed) {
        val errMsg = try {
            JSONObject(body).optString("err_msg").trim()
        } catch (_: Throwable) {
            ""
        }
        return context.getString(R.string.error_baidu_api_failed, errorCode, errMsg)
    }
    return context.getString(resId)
}
