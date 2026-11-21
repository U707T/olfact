package cn.olfact.download.util

import cn.olfact.download.DownloadSpec
import cn.olfact.download.MediaProbe
import cn.olfact.download.util.Http.await
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class HttpCancellationTest {
    @Test fun `cancel after headers closes the network call during body consumption`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("slow-body").setBodyDelay(5, TimeUnit.SECONDS))
            server.start()
            val spec = DownloadSpec(server.url("/body").toString(), fileName = "body")
            val call = OkHttpClient().newCall(Http.request(spec))
            val reading = CompletableDeferred<Unit>()
            val job = launch {
                call.await { response ->
                    reading.complete(Unit)
                    response.body.string()
                }
            }
            withTimeout(2_000) { reading.await() }
            withTimeout(2_000) { job.cancelAndJoin() }
            assertTrue(call.isCanceled())
        }
    }

    @Test fun `cancel waits for the body consumer before the download job completes`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("payload"))
            server.start()
            val call = OkHttpClient().newCall(Http.request(DownloadSpec(server.url("/body").toString(), fileName = "body")))
            val reading = CompletableDeferred<Unit>()
            val exited = CompletableDeferred<Unit>()
            val release = java.util.concurrent.CountDownLatch(1)
            val job = launch {
                call.await { response ->
                    reading.complete(Unit)
                    try {
                        check(release.await(5, TimeUnit.SECONDS))
                        response.body.string()
                    } finally {
                        exited.complete(Unit)
                    }
                }
            }
            try {
                withTimeout(2_000) { reading.await() }
                job.cancel()
                assertNull("cleanup must not run while the body consumer still owns its output", withTimeoutOrNull(200) { job.join() })
                assertTrue(call.isCanceled())
            } finally {
                release.countDown()
                withTimeout(2_000) { job.cancelAndJoin(); exited.await() }
            }
        }
    }

    @Test fun `media probing propagates cancellation instead of starting fallback requests`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeadersDelay(5, TimeUnit.SECONDS))
            server.start()
            val spec = DownloadSpec(server.url("/head").toString(), fileName = "head")
            val job = launch(Dispatchers.IO) { MediaProbe.probe(OkHttpClient(), spec) }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            withTimeout(2_000) { job.cancelAndJoin() }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `referer from custom headers is preserved when no explicit override exists`() {
        val spec = DownloadSpec("https://example.com/media", fileName = "media",
            headers = mapOf("referer" to "https://example.com/page"))
        assertEquals("https://example.com/page", Http.request(spec).header("Referer"))
        assertEquals("https://example.com/override", Http.request(spec.copy(referer = "https://example.com/override")).header("Referer"))
    }
}
