// Tests Baidu access_token fetching and caching against a mock server.
package com.brycewg.asrkb.asr

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BaiduAsrTokenManagerTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** 将百度鉴权主备域名都改写到本地 mock 服务器 */
    private fun rewriteClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val mockUrl = server.url("/")
            val request = chain.request()
            val rewritten = request.newBuilder()
                .url(
                    request.url.newBuilder()
                        .scheme(mockUrl.scheme)
                        .host(mockUrl.host)
                        .port(mockUrl.port)
                        .build()
                )
                .build()
            chain.proceed(rewritten)
        }
        .build()

    @Test
    fun fetchesTokenAndReusesCacheWithinExpiry() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"access_token":"tok-1","expires_in":2592000}""")
        )
        var nowMs = 0L
        val manager = BaiduAsrTokenManager(rewriteClient()) { nowMs }

        assertEquals("tok-1", manager.fetchToken("api-key", "secret"))
        // 有效期内再次获取不发起网络请求
        assertEquals("tok-1", manager.fetchToken("api-key", "secret"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun refetchesTokenAfterExpiry() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"access_token":"tok-1","expires_in":10}""")
        )
        server.enqueue(
            MockResponse().setBody("""{"access_token":"tok-2","expires_in":10}""")
        )
        var nowMs = 0L
        val manager = BaiduAsrTokenManager(rewriteClient()) { nowMs }

        assertEquals("tok-1", manager.fetchToken("api-key", "secret"))
        // expires_in=10s，安全余量 5 分钟：直接视为需要刷新
        nowMs = 6_000L
        assertEquals("tok-2", manager.fetchToken("api-key", "secret"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun differentApiKeyMissesCache() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"access_token":"tok-1","expires_in":2592000}""")
        )
        server.enqueue(
            MockResponse().setBody("""{"access_token":"tok-2","expires_in":2592000}""")
        )
        val manager = BaiduAsrTokenManager(rewriteClient())

        assertEquals("tok-1", manager.fetchToken("api-key-1", "secret"))
        assertEquals("tok-2", manager.fetchToken("api-key-2", "secret"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun httpFailureTriesFallbackEndpointAndReturnsNull() = runTest {
        server.enqueue(MockResponse().setResponseCode(400))
        server.enqueue(MockResponse().setResponseCode(400))
        val manager = BaiduAsrTokenManager(rewriteClient())

        assertNull(manager.fetchToken("api-key", "secret"))
        // 主备两个鉴权域名各请求一次
        assertEquals(2, server.requestCount)
    }

    @Test
    fun malformedResponseBodyReturnsNull() = runTest {
        server.enqueue(MockResponse().setBody("not-json"))
        server.enqueue(MockResponse().setBody("""{"expires_in":2592000}"""))
        val manager = BaiduAsrTokenManager(rewriteClient())

        assertNull(manager.fetchToken("api-key", "secret"))
        assertEquals(2, server.requestCount)
    }
}
