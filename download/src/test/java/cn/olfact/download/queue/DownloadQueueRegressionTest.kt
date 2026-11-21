package cn.olfact.download.queue

import android.content.ContextWrapper
import cn.olfact.download.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DownloadQueueRegressionTest {
    @get:Rule val temp = TemporaryFolder()

    private fun context() = object : ContextWrapper(null) {
        override fun getFilesDir(): File = File(temp.root, "files").also { it.mkdirs() }
        override fun getCacheDir(): File = File(temp.root, "cache").also { it.mkdirs() }
        override fun getExternalFilesDir(type: String?): File = File(temp.root, "external").also { it.mkdirs() }
    }
    private fun record(id: String, status: DownloadStatus, engine: DownloadEngine = DownloadEngine.SYSTEM) =
        DownloadRecord(id, DownloadSpec("https://example.com/$id", fileName = id), engine, status,
            systemDownloadId = if (engine == DownloadEngine.SYSTEM) 42 else null)

    @Test fun `case sensitive media paths do not collapse into the same active download`() {
        val queue = DownloadQueue(context(), InMemoryDownloadStore(), { DownloadConfig(notifyProgress = false) })
        val upper = queue.enqueue(DownloadSpec("https://example.com/A.mp4", fileName = "video"))
        val lower = queue.enqueue(DownloadSpec("https://example.com/a.mp4", fileName = "video"))
        assertNotEquals(upper.id, lower.id)
        assertEquals(upper.id, queue.enqueue(upper.spec).id)
    }

    @Test fun `completed system downloads are retained when only removing records`() {
        val completed = record("done", DownloadStatus.COMPLETED)
        assertFalse(shouldRemoveSystemDownload(completed, deleteFile = false))
        assertTrue(shouldRemoveSystemDownload(completed, deleteFile = true))
        assertTrue(shouldRemoveSystemDownload(record("running", DownloadStatus.RUNNING), deleteFile = false))
    }

    @Test fun `retry resets a failed system download id instead of tracking its failure forever`() {
        val store = InMemoryDownloadStore(listOf(record("failed", DownloadStatus.FAILED)))
        val queue = DownloadQueue(context(), store, { DownloadConfig(notifyProgress = false) })
        queue.retry("failed")
        assertEquals(DownloadStatus.QUEUED, store.get("failed")!!.status)
        assertNull(store.get("failed")!!.systemDownloadId)
    }

    @Test fun `pausing one download does not cancel the rest of the queue`() = runBlocking {
        MockWebServer().use { server ->
            val started = CountDownLatch(2)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "HEAD") return MockResponse().setResponseCode(405)
                    if (request.getHeader("Range") != null) return MockResponse().setResponseCode(416)
                    started.countDown()
                    return MockResponse().setBody("payload").setBodyDelay(400, TimeUnit.MILLISECONDS)
                }
            }
            server.start()
            val store = InMemoryDownloadStore()
            val queue = DownloadQueue(context(), store,
                { DownloadConfig(maxConcurrentTasks = 2, notifyProgress = false, preferSystemDownloader = false) },
                client = OkHttpClient())
            val first = queue.enqueue(DownloadSpec(server.url("/first.bin").toString(), fileName = "same"))
            val second = queue.enqueue(DownloadSpec(server.url("/second.bin").toString(), fileName = "same"))
            val processing = launch(Dispatchers.IO) { queue.processQueue() }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            queue.pause(first.id)
            withTimeout(5_000) { processing.join() }
            assertEquals(DownloadStatus.PAUSED, store.get(first.id)!!.status)
            assertEquals(DownloadStatus.COMPLETED, store.get(second.id)!!.status)
            assertEquals("payload", File(store.get(second.id)!!.filePath!!).readText())
        }
    }

    @Test fun `new downloads use spare concurrency while an earlier task is running`() = runBlocking {
        MockWebServer().use { server ->
            val firstStarted = CountDownLatch(1)
            val secondStarted = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "HEAD") return MockResponse().setResponseCode(405)
                    if (request.getHeader("Range") != null) return MockResponse().setResponseCode(416)
                    if (request.path == "/first.bin") {
                        firstStarted.countDown()
                        return MockResponse().setBody("first").setBodyDelay(5, TimeUnit.SECONDS)
                    }
                    secondStarted.countDown()
                    return MockResponse().setBody("second")
                }
            }
            server.start()
            val store = InMemoryDownloadStore()
            val queue = DownloadQueue(context(), store,
                { DownloadConfig(maxConcurrentTasks = 2, notifyProgress = false, preferSystemDownloader = false) },
                client = OkHttpClient())
            val first = queue.enqueue(DownloadSpec(server.url("/first.bin").toString(), fileName = "first"))
            val processing = launch(Dispatchers.IO) { queue.processQueue() }
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
            val second = queue.enqueue(DownloadSpec(server.url("/second.bin").toString(), fileName = "second"))
            try {
                assertTrue("a newly queued task should start before the first one finishes", secondStarted.await(2, TimeUnit.SECONDS))
            } finally { queue.pause(first.id) }
            withTimeout(5_000) { processing.join() }
            assertEquals(DownloadStatus.COMPLETED, store.get(second.id)!!.status)
        }
    }

    @Test fun `removing a paused task waits for its body reader before deleting temporary files`() = runBlocking {
        val reading = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val bodySource = object : okio.Source {
            override fun read(sink: okio.Buffer, byteCount: Long): Long {
                reading.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS))
                return -1
            }
            override fun timeout() = okio.Timeout.NONE
            override fun close() = Unit
        }.buffer()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val code = when {
                request.method == "HEAD" -> 405
                request.header("Range") != null -> 416
                else -> 200
            }
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(code).message("test").body(object : okhttp3.ResponseBody() {
                    override fun contentType(): okhttp3.MediaType? = null
                    override fun contentLength() = -1L
                    override fun source() = if (code == 200) bodySource else okio.Buffer()
                }).build()
        }.build()
        val context = context()
        val store = InMemoryDownloadStore()
        val queue = DownloadQueue(context, store,
            { DownloadConfig(notifyProgress = false, preferSystemDownloader = false) }, client = client)
        val item = queue.enqueue(DownloadSpec("https://example.com/file", fileName = "file"))
        val workDir = DownloadPaths.workDir(context, item.id)
        val processing = launch(Dispatchers.IO) { queue.processQueue() }
        try {
            withTimeout(2_000) { reading.await() }
            assertTrue(workDir.exists())
            queue.pause(item.id)
            queue.remove(item.id, deleteFile = false)
            assertNull("paused jobs must remain joinable until the body reader exits", withTimeoutOrNull(200) {
                while (workDir.exists()) delay(10)
            })
        } finally {
            release.countDown()
            withTimeout(2_000) { processing.join() }
        }
        withTimeout(2_000) { while (workDir.exists()) delay(10) }
        assertNull(store.get(item.id))
    }

    @Test fun `worker cancellation keeps interrupted jobs queued for rescheduling`() = runBlocking {
        MockWebServer().use { server ->
            val started = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "HEAD") return MockResponse().setResponseCode(405)
                    if (request.getHeader("Range") != null) return MockResponse().setResponseCode(416)
                    started.countDown()
                    return MockResponse().setBody("payload").setBodyDelay(5, TimeUnit.SECONDS)
                }
            }
            server.start()
            val store = InMemoryDownloadStore()
            val queue = DownloadQueue(context(), store,
                { DownloadConfig(notifyProgress = false, preferSystemDownloader = false) }, client = OkHttpClient())
            val item = queue.enqueue(DownloadSpec(server.url("/file.bin").toString(), fileName = "file"))
            val processing = launch(Dispatchers.IO) { queue.processQueue() }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            withTimeout(2_000) { processing.cancelAndJoin() }
            assertEquals(DownloadStatus.QUEUED, store.get(item.id)!!.status)
        }
    }
}
