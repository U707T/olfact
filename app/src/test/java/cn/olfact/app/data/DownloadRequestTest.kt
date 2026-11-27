package cn.olfact.app.data

import cn.olfact.download.DownloadKind
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadRequestTest {
    @Test fun extensionlessPlaylistUsesDeclaredMime() {
        val headers = mutableMapOf("Cookie" to "test")
        val spec = downloadSpecForRequest("https://cdn.example/play?id=8", null, "https://page.example/8",
            "https://page.example/", "browser", "application/vnd.apple.mpegurl", headers)
        headers.clear()
        assertEquals(DownloadKind.HLS, spec.kind)
        assertEquals("play", spec.fileName)
        assertEquals("https://page.example/", spec.referer)
        assertEquals(mapOf("Cookie" to "test"), spec.headers)
    }
}
