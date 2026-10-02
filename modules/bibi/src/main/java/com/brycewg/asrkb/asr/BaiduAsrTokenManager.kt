/**
 * 百度语音识别 access_token 获取与缓存。
 *
 * 鉴权文档：https://cloud.baidu.com/doc/SPEECH/s/Ilbpcv73p
 * 使用 ApiKey + SecretKey 换取 access_token，有效期由服务端返回（通常 30 天），
 * 过期前自动复用缓存，仅在网络失败等场景下向上游返回 null。
 *
 * 归属模块：asr
 */
package com.brycewg.asrkb.asr

import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

internal class BaiduAsrTokenManager(
    private val httpClient: OkHttpClient,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {

    companion object {
        private const val TAG = "BaiduAsrTokenManager"
        private const val TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token"
        private const val FALLBACK_TOKEN_URL = "https://openapi.baidu.com/oauth/2.0/token"
        private const val GRANT_TYPE = "client_credentials"

        // 提前 5 分钟视为过期，避免临界点上的请求携带即将失效的 token
        private const val EXPIRY_SAFETY_MS = 5 * 60 * 1000L
    }

    private data class CacheEntry(
        val apiKey: String,
        val token: String,
        val expiresAtMs: Long
    )

    private val mutex = Mutex()

    @Volatile
    private var cache: CacheEntry? = null

    /**
     * 获取可用的 access_token；失败（网络/鉴权错误）返回 null。
     * 同一 ApiKey 的缓存 token 在过期前直接复用。
     */
    suspend fun fetchToken(apiKey: String, secretKey: String): String? = mutex.withLock {
        cache?.takeIf {
            it.apiKey == apiKey && it.expiresAtMs - EXPIRY_SAFETY_MS > nowMs()
        }?.token
            ?: requestToken(apiKey, secretKey, TOKEN_URL)
            ?: requestToken(apiKey, secretKey, FALLBACK_TOKEN_URL)
    }

    fun clearCache() {
        cache = null
    }

    private fun requestToken(apiKey: String, secretKey: String, baseUrl: String): String? = try {
        val request = Request.Builder()
            .url("$baseUrl?grant_type=$GRANT_TYPE&client_id=$apiKey&client_secret=$secretKey")
            .tag(
                ApiLogMeta::class.java,
                ApiLogRecorder.meta(
                    category = "ASR",
                    vendor = "baidu",
                    model = "access_token",
                    requestStructure = "oauth token query params=grant_type, client_id, client_secret"
                )
            )
            .post(FormBody.Builder().build())
            .build()
        httpClient.newCall(request).execute().use { resp ->
            val bodyStr = resp.body.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w(TAG, "Baidu token request failed: http ${resp.code}")
                return@use null
            }
            val obj = JSONObject(bodyStr)
            val token = obj.optString("access_token").trim()
            val expiresInSec = obj.optLong("expires_in", 0L)
            if (token.isEmpty() || expiresInSec <= 0L) {
                Log.w(TAG, "Baidu token response missing access_token/expires_in")
                return@use null
            }
            val entry = CacheEntry(
                apiKey = apiKey,
                token = token,
                expiresAtMs = nowMs() + TimeUnit.SECONDS.toMillis(expiresInSec)
            )
            cache = entry
            entry.token
        }
    } catch (t: Throwable) {
        Log.w(TAG, "Baidu token request error", t)
        null
    }
}
