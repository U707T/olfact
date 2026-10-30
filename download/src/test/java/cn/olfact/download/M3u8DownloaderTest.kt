package cn.olfact.download

import cn.olfact.download.m3u8.MergeOutcome
import cn.olfact.download.m3u8.SegmentMerger
import cn.olfact.download.m3u8.M3u8Downloader
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class M3u8DownloaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer

    private val master = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
        360p/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1280x720
        720p/index.m3u8
    """.trimIndent()

    private val media = """
        #EXTM3U
        #EXT-X-TARGETDURATION:4
        #EXTINF:4.0,
        seg0.ts
        #EXTINF:4.0,
        seg1.ts
        #EXT-X-CUE-OUT:4
        #EXTINF:4.0,
        ads/ad0.ts
        #EXT-X-CUE-IN
        #EXTINF:4.0,
        seg2.ts
        #EXT-X-ENDLIST
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/master.m3u8") -> text(master)
                    path.startsWith("/720p/index.m3u8") -> text(media)
                    path.startsWith("/360p/index.m3u8") -> text(media.replace("seg", "low"))
                    path.contains("/ads/") -> text("AD|")
                    path.contains("seg") -> text("SEG:${path.substringAfterLast('/')}|")
                    path.contains("low") -> text("LOW:${path.substringAfterLast('/')}|")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun text(body: String): MockResponse =
        MockResponse().addHeader("Content-Type", "application/vnd.apple.mpegurl").setBody(body)

    private val fakeMerger = SegmentMerger { segments, outputBase, _ ->
        val out = File(outputBase.parentFile, outputBase.nameWithoutExtension + ".mp4")
        out.writeText(segments.joinToString("") { it.readText() })
        MergeOutcome(out, remuxed = true)
    }

    @Test
    fun `master 选最高码率 下载分片 过滤广告 合并`() = kotlinx.coroutines.runBlocking {
        val downloader = M3u8Downloader(OkHttpClient(), temp.newFolder("work"), 2, fakeMerger)
        val target = File(temp.root, "video.mp4")
        val spec = DownloadSpec(
            url = server.url("/master.m3u8").toString(),
            kind = DownloadKind.HLS,
            fileName = "video",
        )

        val result = downloader.download(spec, target)

        assertEquals("SEG:seg0.ts|SEG:seg1.ts|SEG:seg2.ts|", result.file.readText())
        assertEquals(DownloadEngine.M3U8, result.engine)
        assertTrue("广告分片不应出现在结果里", !result.file.readText().contains("AD"))
        assertTrue(result.message?.contains("过滤") == true)
    }

    @Test
    fun `失败分片会重试`() = kotlinx.coroutines.runBlocking {
        var failures = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (path.endsWith("/seg1.ts") && failures < 2) {
                    failures++
                    return MockResponse().setResponseCode(500)
                }
                return when {
                    path.startsWith("/master.m3u8") -> text(master)
                    path.startsWith("/720p/index.m3u8") -> text(media)
                    path.contains("/ads/") -> text("AD|")
                    path.contains("seg") -> text("SEG:${path.substringAfterLast('/')}|")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val downloader = M3u8Downloader(OkHttpClient(), temp.newFolder("work2"), 2, fakeMerger)
        val target = File(temp.root, "retry.mp4")
        val spec = DownloadSpec(url = server.url("/master.m3u8").toString(), fileName = "retry")

        val result = downloader.download(spec, target)

        assertEquals(2, failures)
        assertTrue(result.file.readText().contains("SEG:seg1.ts"))
    }
}
