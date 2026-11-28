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

    @Test fun `recollecting legacy favorite enriches without changing identity time or order`() {
        val url = "https://cdn.example/video"
        FavoriteStore.importAll(context, listOf(FavoriteStore.Favorite(771, url, url, 1234)))
        FavoriteStore.add(context, "other", "https://other.example/video")
        val order = FavoriteStore.items.map { it.id }
        val result = FavoriteStore.saveOrEnrich(context, "Video title", url,
            "https://user:pass@site.example/watch?token=secret#part", "HLS", "application/vnd.apple.mpegurl")
        assertEquals(FavoriteStore.SaveResult.UPDATED, result)
        assertEquals(order, FavoriteStore.items.map { it.id })
        val favorite = FavoriteStore.items.first { it.url == url }
        assertEquals(771L, favorite.id)
        assertEquals(1234L, favorite.at)
        assertEquals("Video title", favorite.title)
        assertEquals("https://site.example/watch", favorite.sourcePage)
        assertEquals("HLS", favorite.mediaType)
        FavoriteStore.importAll(context, emptyList()) // wait for a real persisted snapshot
        val persisted = JsonStore.readArray(context, "favorites").toString()
        assertTrue(persisted.contains("application/vnd.apple.mpegurl"))
        assertFalse(persisted.contains("secret"))
        assertFalse(persisted.contains("user:pass"))
    }

    @Test fun `recollecting preserves existing title and context and reports unchanged`() {
        val url = "https://cdn.example/video"
        assertEquals(FavoriteStore.SaveResult.ADDED, FavoriteStore.saveOrEnrich(context,
            "My title", url, "https://original.example/watch", "HLS", "video/mp2t"))
        val original = FavoriteStore.items.single()
        assertEquals(FavoriteStore.SaveResult.UNCHANGED, FavoriteStore.saveOrEnrich(context,
            "Other title", url, "https://other.example/watch", "DASH", "video/mp4"))
        assertEquals(listOf(original), FavoriteStore.items)
        assertFalse(FavoriteStore.add(context, "duplicate", url))
    }

    @Test fun `import enriches existing and same batch duplicate with durable receipt`() {
        val url = "https://cdn.example/video"
        FavoriteStore.importAll(context, listOf(FavoriteStore.Favorite(771, "kept", url, 1234)))
        val count = FavoriteStore.importAll(context, listOf(
            FavoriteStore.Favorite(991, "other", url, 9999, "https://site.example/watch?secret=token", "HLS"),
            FavoriteStore.Favorite(992, "other", url, 9999, mimeType = "application/vnd.apple.mpegurl")
        ))
        assertEquals(0, count)
        val favorite = FavoriteStore.items.single()
        assertEquals(771L, favorite.id)
        assertEquals(1234L, favorite.at)
        assertEquals("kept", favorite.title)
        assertEquals("HLS", favorite.mediaType)
        val persisted = JsonStore.readArray(context, "favorites").getJSONObject(0)
        assertEquals("https://site.example/watch", persisted.getString("sourcePage"))
        assertEquals("application/vnd.apple.mpegurl", persisted.getString("mimeType"))
    }

    @Test fun `undo after recollecting cannot create duplicate URL and keeps new identity`() {
        val url = "https://cdn.example/video"
        FavoriteStore.add(context, "old", url, "https://site.example/watch")
        val removed = FavoriteStore.items.single()
        FavoriteStore.remove(context, removed.id)
        FavoriteStore.add(context, "new", url)
        val newId = FavoriteStore.items.single().id
        FavoriteStore.restore(context, removed)
        val favorite = FavoriteStore.items.single()
        assertEquals(newId, favorite.id)
        assertEquals("new", favorite.title)
        assertEquals("https://site.example/watch", favorite.sourcePage)
    }

    @Test fun `unsafe favorite sources are rejected and IPv6 page paths are preserved`() {
        listOf("ftp://host.example/file", "file://host.example/private", "intent://app.example/path",
            "https://site.example:99999/watch", "https://bad host/watch").forEach {
            assertNull(it, safeFavoriteSourcePage(it))
        }
        assertEquals("https://[::1]:8443/watch%20video", safeFavoriteSourcePage("https://[::1]:8443/watch%20video?q=secret#part"))
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
