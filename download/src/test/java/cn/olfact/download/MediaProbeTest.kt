package cn.olfact.download

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking

class MediaProbeTest {

    private lateinit var server: MockWebServer

    private val payload = ByteArray(2_048) { it.toByte() }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun spec() = DownloadSpec(
        url = server.url("/video.mp4").toString(),
        fileName = "video.mp4"
    )

    @Test
    fun `优先用 HEAD 探测长度与 Range`() = runBlocking {
        var sawHead = false
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "HEAD") {
                    sawHead = true
                    return MockResponse()
                        .addHeader("Accept-Ranges", "bytes")
                        .addHeader("Content-Length", payload.size)
                        .addHeader("Content-Type", "video/mp4")
                }
                return MockResponse().setResponseCode(400)
            }
        }
        val result = MediaProbe.probe(okhttp3.OkHttpClient(), spec())
        assertTrue("应该先发 HEAD", sawHead)
        assertEquals(payload.size.toLong(), result.contentLength)
        assertTrue(result.rangeSupported)
        assertEquals("video/mp4", result.contentType)
    }

    @Test
    fun `HEAD 被拒时退回 Range 探测`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "HEAD") return MockResponse().setResponseCode(405)
                val range = request.getHeader("Range")
                if (range == "bytes=0-0") {
                    return MockResponse()
                        .setResponseCode(206)
                        .addHeader("Content-Range", "bytes 0-0/${payload.size}")
                        .setBody(Buffer().write(payload.copyOfRange(0, 1)))
                }
                return MockResponse().setResponseCode(400)
            }
        }
        val result = MediaProbe.probe(okhttp3.OkHttpClient(), spec())
        assertEquals(payload.size.toLong(), result.contentLength)
        assertTrue(result.rangeSupported)
    }

    @Test
    fun `HEAD 没有长度时也退回 Range 探测`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "HEAD") return MockResponse() // 无 Content-Length
                return MockResponse()
                    .setResponseCode(206)
                    .addHeader("Content-Range", "bytes 0-0/1234")
                    .setBody(Buffer().write(ByteArray(1)))
            }
        }
        val result = MediaProbe.probe(okhttp3.OkHttpClient(), spec())
        assertEquals(1234L, result.contentLength)
        assertTrue(result.rangeSupported)
    }

    @Test
    fun `服务器完全不支持 Range 时返回单线程信号`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(200).setBody("hello")
        }
        val result = MediaProbe.probe(okhttp3.OkHttpClient(), spec())
        assertFalse(result.rangeSupported)
    }
}
