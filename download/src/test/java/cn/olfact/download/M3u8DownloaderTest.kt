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

    private val fakeMerger = SegmentMerger { segments, outputBase, init ->
        val out = File(outputBase.parentFile, outputBase.nameWithoutExtension + ".mp4")
        out.writeText((listOfNotNull(init) + segments).joinToString("") { it.readText() })
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

    @Test fun `master requiring external audio fails before downloading video-only variant`() = kotlinx.coroutines.runBlocking {
        val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.path.orEmpty()
                return when (request.path) {
                    "/master.m3u8" -> text("""
                        #EXTM3U
                        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="main",URI="audio.m3u8"
                        #EXT-X-STREAM-INF:BANDWIDTH=1000000,AUDIO="audio"
                        video.m3u8
                    """.trimIndent())
                    else -> text("#EXTM3U\n#EXTINF:1,\nvideo.ts\n#EXT-X-ENDLIST")
                }
            }
        }
        val result = runCatching {
            M3u8Downloader(OkHttpClient(), temp.newFolder("external-audio"), 1, fakeMerger)
                .download(DownloadSpec(server.url("/master.m3u8").toString(), fileName = "audio"),
                    File(temp.root, "external-audio.mp4"))
        }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("外置音轨"))
        assertEquals(listOf("/master.m3u8"), paths.toList())
        assertTrue(!File(temp.root, "external-audio.mp4").exists())
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

    @Test fun `byteranges use explicit and implicit offsets instead of downloading whole resources`() = kotlinx.coroutines.runBlocking {
        val ranges = java.util.Collections.synchronizedList(mutableListOf<String>())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/index.m3u8") return text("""
                    #EXTM3U
                    #EXT-X-BYTERANGE:3@0
                    #EXTINF:1,
                    all.ts
                    #EXT-X-BYTERANGE:3
                    #EXTINF:1,
                    all.ts
                    #EXT-X-ENDLIST
                """.trimIndent())
                val range = request.getHeader("Range").orEmpty()
                ranges += range
                val from = range.substringAfter('=').substringBefore('-').toInt()
                val end = range.substringAfter('-').toInt()
                return MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes $from-$end/6")
                    .setBody("ABCDEF".substring(from, end + 1))
            }
        }
        val result = M3u8Downloader(OkHttpClient(), temp.newFolder("ranges"), 2, fakeMerger)
            .download(DownloadSpec(server.url("/index.m3u8").toString(), fileName = "ranges"), File(temp.root, "ranges.mp4"))
        assertEquals("ABCDEF", result.file.readText())
        assertEquals(setOf("bytes=0-2", "bytes=3-5"), ranges.toSet())
    }

    @Test fun `changing ad filtering cannot reuse a different segment at the same index`() = kotlinx.coroutines.runBlocking {
        val downloader = M3u8Downloader(OkHttpClient(), temp.newFolder("filtered"), 2, fakeMerger)
        val spec = DownloadSpec(server.url("/master.m3u8").toString(), fileName = "same")
        val unfiltered = downloader.download(spec, File(temp.root, "all.mp4"),
            cn.olfact.download.m3u8.AdFilterConfig(enabled = false))
        assertTrue(unfiltered.file.readText().contains("AD|"))
        val filtered = downloader.download(spec, File(temp.root, "filtered.mp4"))
        assertEquals("SEG:seg0.ts|SEG:seg1.ts|SEG:seg2.ts|", filtered.file.readText())
    }

    @Test fun `map decryption uses its own declared key and IV and cache identity tracks that key`() = kotlinx.coroutines.runBlocking {
        val mapKeyA = "AAAAAAAAAAAAAAAA".toByteArray()
        val mapKeyB = "CCCCCCCCCCCCCCCC".toByteArray()
        val mediaKey = "BBBBBBBBBBBBBBBB".toByteArray()
        val ivA = "0x00000000000000000000000000000001"
        val ivB = "0x00000000000000000000000000000002"
        val mediaIv = "0x00000000000000000000000000000003"
        fun encrypt(text: String, key: ByteArray, ivText: String): ByteArray {
            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"),
                javax.crypto.spec.IvParameterSpec(cn.olfact.download.m3u8.Aes128Decryptor.parseIv(ivText, 0)))
            return cipher.doFinal(text.toByteArray())
        }
        var playlistNo = 0
        var initRequests = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/index.m3u8" -> {
                    playlistNo++
                    val mapPath = if (playlistNo == 1) "map-a.key" else "map-b.key"
                    val mapIv = if (playlistNo == 1) ivA else ivB
                    text("""
                        #EXTM3U
                        #EXT-X-KEY:METHOD=AES-128,URI="$mapPath",IV=$mapIv
                        #EXT-X-MAP:URI="init.mp4"
                        #EXT-X-KEY:METHOD=AES-128,URI="media.key",IV=$mediaIv
                        #EXTINF:1,
                        media.ts
                        #EXT-X-ENDLIST
                    """.trimIndent())
                }
                "/map-a.key" -> MockResponse().setBody(Buffer().write(mapKeyA))
                "/map-b.key" -> MockResponse().setBody(Buffer().write(mapKeyB))
                "/media.key" -> MockResponse().setBody(Buffer().write(mediaKey))
                "/init.mp4" -> {
                    initRequests++
                    val first = playlistNo == 1
                    MockResponse().setBody(Buffer().write(encrypt(if (first) "INIT-A" else "INIT-B",
                        if (first) mapKeyA else mapKeyB, if (first) ivA else ivB)))
                }
                "/media.ts" -> MockResponse().setBody(Buffer().write(encrypt("MEDIA", mediaKey, mediaIv)))
                else -> MockResponse().setResponseCode(404)
            }
        }
        val downloader = M3u8Downloader(OkHttpClient(), temp.newFolder("map-identity"), 1, fakeMerger)
        val spec = DownloadSpec(server.url("/index.m3u8").toString(), fileName = "map")
        val first = downloader.download(spec, File(temp.root, "map-one.mp4"))
        val second = downloader.download(spec, File(temp.root, "map-two.mp4"))
        assertEquals("INIT-AMEDIA", first.file.readText())
        assertEquals("INIT-BMEDIA", second.file.readText())
        assertEquals(2, initRequests)
    }

    @Test fun `relative segments are resolved against the redirected playlist URL`() = kotlinx.coroutines.runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/redirect" -> MockResponse().setResponseCode(302).addHeader("Location", "/actual/index.m3u8")
                "/actual/index.m3u8" -> text("#EXTM3U\n#EXTINF:1,\nsegment.ts\n#EXT-X-ENDLIST")
                "/actual/segment.ts" -> text("CORRECT")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val result = M3u8Downloader(OkHttpClient(), temp.newFolder("redirect"), 1, fakeMerger)
            .download(DownloadSpec(server.url("/redirect").toString(), fileName = "redirect"), File(temp.root, "redirect.mp4"))
        assertEquals("CORRECT", result.file.readText())
    }

    @Test fun `unsupported encryption fails instead of reporting encrypted bytes as completed`() = kotlinx.coroutines.runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = text("""
                #EXTM3U
                #EXT-X-KEY:METHOD=SAMPLE-AES,URI="key"
                #EXTINF:1,
                segment.ts
                #EXT-X-ENDLIST
            """.trimIndent())
        }
        val result = runCatching {
            M3u8Downloader(OkHttpClient(), temp.newFolder("unsupported"), 1, fakeMerger)
                .download(DownloadSpec(server.url("/index.m3u8").toString(), fileName = "unsupported"), File(temp.root, "unsupported.mp4"))
        }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("加密"))
    }
}
