package cn.olfact.app.data

import cn.olfact.download.DownloadEngine
import cn.olfact.download.DownloadFacade
import cn.olfact.download.DownloadRecord
import cn.olfact.download.DownloadSpec
import cn.olfact.download.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual DownloadAdapter; facade records delegation, no queue or download IO is simulated. */
class DownloadAdapterDispatchTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun cleanup() { scope.coroutineContext[Job]!!.cancel() }

    @Test(timeout = 5_000) fun `queue operations run off caller thread in request order`() {
        val facade = DownloadFacade()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val calls = Collections.synchronizedList(ArrayList<String>())
        val threads = Collections.synchronizedList(ArrayList<Thread>())
        val caller = Thread.currentThread()
        facade.onOperation = { call ->
            calls += call
            threads += Thread.currentThread()
            if (call.startsWith("pause:")) {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
            }
            if (call.startsWith("remove:")) done.countDown()
        }
        val adapter = DownloadAdapter(facade, scope, android.content.Context())
        try {
            adapter.pause("one")
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            adapter.resume("one")
            adapter.remove("one", deleteFile = false)
            assertEquals(listOf("pause:one"), calls.toList())
            release.countDown()
            assertTrue(done.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("pause:one", "resume:one", "remove:one:false"), calls.toList())
            assertTrue(threads.all { it !== caller })
        } finally { release.countDown() }
    }

    @Test(timeout = 5_000) fun `clear finished delegates one batch with file deletion disabled`() {
        val facade = DownloadFacade()
        facade.queue.items.value = listOf(
            DownloadRecord("done", DownloadSpec(url = "https://example/a", fileName = "a"), DownloadEngine.CHUNKED, DownloadStatus.COMPLETED),
            DownloadRecord("active", DownloadSpec(url = "https://example/b", fileName = "b"), DownloadEngine.CHUNKED, DownloadStatus.RUNNING),
            DownloadRecord("cancelled", DownloadSpec(url = "https://example/c", fileName = "c"), DownloadEngine.CHUNKED, DownloadStatus.CANCELED)
        )
        val calls = Collections.synchronizedList(ArrayList<String>())
        val done = CountDownLatch(1)
        facade.onOperation = { calls += it; done.countDown() }
        DownloadAdapter(facade, scope, android.content.Context()).clearFinished()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("removeAll:done, cancelled:false"), calls.toList())
    }
}
