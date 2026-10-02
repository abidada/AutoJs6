// Tests Baidu ASR REST response parsing and error mapping.
package com.brycewg.asrkb.asr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BaiduFileAsrEngineTest {

    @Test
    fun parsesSuccessResponseText() {
        assertEquals(
            "北京天气",
            parseBaiduAsrText("""{"err_no":0,"err_msg":"success.","sn":"sn-1","result":["北京天气"]}""")
        )
        assertEquals(
            "hello world",
            parseBaiduAsrText("""{"err_no":0,"result":[" hello world ", "candidate 2"]}""")
        )
    }

    @Test
    fun missingOrMalformedTextReturnsEmpty() {
        assertEquals("", parseBaiduAsrText("""{"err_no":0,"result":[]}"""))
        assertEquals("", parseBaiduAsrText("""{"err_no":0}"""))
        assertEquals("", parseBaiduAsrText("not-json"))
        assertEquals("", parseBaiduAsrText(""))
    }

    @Test
    fun parsesErrorCodeFromResponse() {
        assertEquals(0, parseBaiduAsrErrorCode("""{"err_no":0,"result":["ok"]}"""))
        assertEquals(3302, parseBaiduAsrErrorCode("""{"err_no":3302,"err_msg":"authentication failed"}"""))
        assertEquals(-1, parseBaiduAsrErrorCode("""{"err_msg":"success."}"""))
        assertEquals(-1, parseBaiduAsrErrorCode("not-json"))
        assertEquals(-1, parseBaiduAsrErrorCode(""))
    }

    @Test
    fun endpointAndDevPidMatchBaiduStandardRestApi() {
        assertEquals("https://vop.baidu.com/server_api", BaiduFileAsrEngine.ENDPOINT)
        assertEquals(1537, BaiduFileAsrEngine.DEV_PID)
    }

    @Test
    fun knownErrorCodesMapToDedicatedMessages() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val authFailed = mapBaiduAsrError(context, 3302, """{"err_no":3302}""", 200)
        val audioQuality = mapBaiduAsrError(context, 3301, """{"err_no":3301}""", 200)
        val qpsLimit = mapBaiduAsrError(context, 3304, """{"err_no":3304}""", 200)
        val dailyLimit = mapBaiduAsrError(context, 3305, """{"err_no":3305}""", 200)
        val audioTooLong = mapBaiduAsrError(context, 3308, """{"err_no":3308}""", 200)

        assertTrue(authFailed.isNotBlank())
        assertTrue(audioQuality.isNotBlank())
        assertTrue(qpsLimit.isNotBlank())
        assertTrue(dailyLimit.isNotBlank())
        assertTrue(audioTooLong.isNotBlank())
        assertNotEquals(authFailed, audioQuality)
        assertNotEquals(qpsLimit, dailyLimit)
        // 服务端繁忙类错误码共用同一文案
        assertEquals(
            mapBaiduAsrError(context, 3303, """{"err_no":3303}""", 200),
            mapBaiduAsrError(context, 3307, """{"err_no":3307}""", 200)
        )
    }

    @Test
    fun unknownErrorCodeCarriesCodeInMessage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val message = mapBaiduAsrError(context, 4999, """{"err_no":4999,"err_msg":"boom"}""", 200)
        assertTrue("message should carry error code: $message", message.contains("4999"))
        assertTrue("message should carry err_msg: $message", message.contains("boom"))
    }

    @Test
    fun nonJsonResponseFallsBackToHttpDetail() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val message = mapBaiduAsrError(context, -1, "gateway timeout", 504)
        assertTrue("message should carry http code: $message", message.contains("504"))
    }
}
