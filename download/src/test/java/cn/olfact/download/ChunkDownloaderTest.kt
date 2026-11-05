package cn.olfact.download

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChunkDownloaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer

    private val payload = ByteArray(1_000) { (it % 251).toByte() }

    private val rangeRegex = Regex("""bytes=(\d+)-(\d+)""")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                if (request.method == "HEAD") {
                    return MockResponse()
                        .addHeader("Accept-Ranges", "bytes")
                        .addHeader("Content-Length", payload.size)
                }
                if (range != null) {
                    val match = rangeRegex.find(range) ?: return MockResponse().setResponseCode(416)
                    val start = match.groupValues[1].toInt()
                    val end = match.groupValues[2].toInt().coerceAtMost(payload.size - 1)
                    val slice = payload.copyOfRange(start, end + 1)
                    return MockResponse()
                        .setResponseCode(206)
                        .addHeader("Content-Range", "bytes $start-$end/${payload.size}")
                        .addHeader("Accept-Ranges", "bytes")
                        .setBody(Buffer().write(slice))
                }
                return MockResponse()
                    .addHeader("Accept-Ranges", "bytes")
                    .setBody(Buffer().write(payload))
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `分块下载结果与源文件逐字节一致`() = kotlinx.coroutines.runBlocking {
        val downloader = ChunkDownloader(OkHttpClient(), temp.newFolder("parts"))
        val target = File(temp.root, "out.bin")
        val spec = DownloadSpec(url = server.url("/file.bin").toString(), fileName = "out.bin")

        var lastProgress = -1L
        val result = downloader.download(spec, target, threadCount = 4, chunkSize = 100L) { done, _ ->
            lastProgress = done
        }

        assertArrayEquals(payload, result.file.readBytes())
        assertEquals(payload.size.toLong(), result.bytes)
        assertEquals(payload.size.toLong(), lastProgress)
        assertFalse("part 文件应已清理", File(temp.root, "parts").listFiles().orEmpty().any { it.name.contains("part") })
    }

    @Test
    fun `服务器不支持 Range 时退化为单线程`() = kotlinx.coroutines.runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setBody(Buffer().write(payload))
        }
        val downloader = ChunkDownloader(OkHttpClient(), temp.newFolder("parts2"))
        val target = File(temp.root, "single.bin")
        val spec = DownloadSpec(url = server.url("/single.bin").toString(), fileName = "single.bin")

        val result = downloader.download(spec, target)

        assertArrayEquals(payload, result.file.readBytes())
        assertEquals("服务器不支持分块，已单线程下载", result.message)
    }

    @Test
    fun `已存在的 part 会续传而不是重下`() = kotlinx.coroutines.runBlocking {
        val work = temp.newFolder("parts3")
        // 预置第一块的前 50 字节
        File(work, "resume.part0").writeBytes(payload.copyOfRange(0, 50))
        val downloader = ChunkDownloader(OkHttpClient(), work)
        val target = File(temp.root, "resume.bin")
        val spec = DownloadSpec(url = server.url("/resume.bin").toString(), fileName = "resume")

        val result = downloader.download(spec, target, threadCount = 2, chunkSize = 500L)

        assertArrayEquals(payload, result.file.readBytes())
        assertTrue("应该只补缺失的字节，而不是重下整块", server.requestCount >= 2)
    }

    @Test
    fun `服务器谎报 Range 时失败而不是产出坏文件`() = kotlinx.coroutines.runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "HEAD") {
                    // 声称支持 Range
                    return MockResponse()
                        .addHeader("Accept-Ranges", "bytes")
                        .addHeader("Content-Length", payload.size)
                }
                // 真正 GET 时无视 Range，直接回 200 + 整个文件
                return MockResponse().setBody(Buffer().write(payload))
            }
        }
        val work = temp.newFolder("parts4")
        val target = File(temp.root, "bad.bin")
        val spec = DownloadSpec(url = server.url("/bad.bin").toString(), fileName = "bad.bin")

        val failure = runCatching {
            ChunkDownloader(OkHttpClient(), work).download(spec, target, threadCount = 2, chunkSize = 256L)
        }

        assertTrue("应该失败而不是继续合并", failure.isFailure)
        assertFalse("不能产出大小不对的文件", target.exists())
    }
}
