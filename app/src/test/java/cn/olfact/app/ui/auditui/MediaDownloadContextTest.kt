package cn.olfact.app.ui.auditui

import cn.olfact.app.ui.player.mediaDownloadContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaDownloadContextTest {

    @Test
    fun sniffedRequestHeadersAndRefererArePreserved() {
        val headers = linkedMapOf(
            "Cookie" to "sid=token",
            "Authorization" to "Bearer token",
            "user-agent" to "custom-agent",
            "referer" to "https://media.example/watch"
        )

        val context = mediaDownloadContext(
            sniffedPageUrl = "https://media.example/watch",
            currentPageUrl = "https://other.example/",
            headers = headers,
            mimeType = "video/mp4"
        )

        assertEquals("https://media.example/watch", context.pageUrl)
        assertEquals("https://media.example/watch", context.referer)
        assertEquals("custom-agent", context.userAgent)
        assertEquals("video/mp4", context.mimeType)
        assertEquals(headers, context.headers)
    }

    @Test
    fun fallsBackToCurrentPageWhenSnifferHasNoPageAndNoHeaders() {
        val context = mediaDownloadContext(
            sniffedPageUrl = " ",
            currentPageUrl = "https://current.example/page",
            headers = emptyMap(),
            mimeType = null
        )

        assertEquals("https://current.example/page", context.pageUrl)
        assertEquals("https://current.example/page", context.referer)
        assertNull(context.userAgent)
        assertNull(context.mimeType)
    }
}
