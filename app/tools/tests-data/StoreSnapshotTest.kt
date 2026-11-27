package cn.olfact.app.data

import android.content.Context
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Actual stores and JsonPersist; platform preferences are in-memory. */
class StoreSnapshotTest {
    private lateinit var context: Context
    @Before fun setup() {
        context = Context()
        AppSettings.reset()
        HistoryStore.clear(context)
        FavoriteStore.items.forEach { FavoriteStore.remove(context, it.id) }
    }
    @After fun cleanup() {
        HistoryStore.clear(context)
        FavoriteStore.items.forEach { FavoriteStore.remove(context, it.id) }
        AppSettings.reset()
    }

    @Test fun `negative restored history limit behaves as zero without throwing`() {
        AppSettings.set(K.HISTORY_LIMIT, "-1")
        HistoryStore.record(context, "title", "https://record.example")
        assertTrue(HistoryStore.items.isEmpty())
        HistoryStore.importAll(context, listOf(HistoryEntry("old", "https://import.example", 123)))
        assertTrue(HistoryStore.items.isEmpty())
        HistoryStore.restore(context, HistoryEntry("restored", "https://restore.example", 456))
        HistoryStore.trim(context)
        assertTrue(HistoryStore.items.isEmpty())
    }

    @Test fun `favorite source page strips credentials query and fragment`() {
        assertEquals(
            "https://site.example/watch",
            safeFavoriteSourcePage("https://user:pass@site.example/watch?id=token#section")
        )
        assertNull(safeFavoriteSourcePage("javascript:alert(1)"))
        assertEquals("https://site.example/watch", safeFavoriteSourcePage("https://user:pass@site.example/watch"))
    }

    @Test fun `favorite restore waits for durable snapshot and excludes source URL tokens`() {
        val imported = FavoriteStore.Favorite(
            id = 771,
            title = "protected media",
            url = "https://cdn.example/video.m3u8",
            at = 1234,
            sourcePage = "https://user:pass@site.example/watch?id=private#fragment",
            mediaType = "HLS",
            mimeType = "application/vnd.apple.mpegurl"
        )
        FavoriteStore.importAll(context, listOf(imported))

        val persisted = JsonStore.readArray(context, "favorites")
        assertTrue(persisted.toString().contains("video.m3u8"))
        assertTrue(persisted.toString().contains("application/vnd.apple.mpegurl"))
        assertFalse(persisted.toString().contains("user:pass"))
        assertFalse(persisted.toString().contains("private"))
        assertEquals("https://site.example/watch", persisted.getJSONObject(0).getString("sourcePage"))
    }

    @Test fun `favorite snapshot remains unchanged after later add and remove`() {
        FavoriteStore.add(context, "first", "https://one.example")
        val first = FavoriteStore.items.single()
        val exported = FavoriteStore.items
        FavoriteStore.add(context, "second", "https://two.example")
        FavoriteStore.remove(context, first.id)
        assertEquals(listOf(first), exported)
        assertEquals(listOf("https://two.example"), FavoriteStore.items.map { it.url })
    }

    @Test(timeout = 5_000) fun `favorite export snapshot can be iterated while another thread edits store`() {
        repeat(40) { FavoriteStore.add(context, "item$it", "https://initial$it.example") }
        val exported = FavoriteStore.items
        val ids = exported.map { it.id }
        val begin = CountDownLatch(1)
        val done = CountDownLatch(2)
        val failure = AtomicReference<Throwable>()
        thread(isDaemon = true) {
            try {
                begin.await()
                repeat(40) { index ->
                    FavoriteStore.add(context, "new$index", "https://new$index.example")
                    FavoriteStore.remove(context, ids[index])
                }
            } catch (error: Throwable) { failure.compareAndSet(null, error) }
            finally { done.countDown() }
        }
        thread(isDaemon = true) {
            try {
                begin.await()
                repeat(40) {
                    val json = JSONArray()
                    exported.forEach { json.put(JsonStore.objOf("id" to it.id, "url" to it.url)) }
                    assertEquals(ids.size, json.length())
                    FavoriteStore.items.forEach { assertTrue(it.url.isNotBlank()) }
                }
            } catch (error: Throwable) { failure.compareAndSet(null, error) }
            finally { done.countDown() }
        }
        begin.countDown()
        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertNull(failure.get())
        assertEquals(ids, exported.map { it.id })
    }
}
