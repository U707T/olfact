package cn.olfact.app.data

import android.content.Context
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Actual BrowserSession and JsonStore with in-memory settings/SharedPreferences substitutes. */
class BrowserSessionPersistenceTest {
    private lateinit var context: Context
    @Before fun setup() {
        context = Context()
        AppSettings.reset()
        BrowserSession.resetForTest()
    }
    @After fun cleanup() {
        AppSettings.reset()
        BrowserSession.resetForTest()
    }

    @Test fun `never mode erases prior saved tabs and selection`() {
        BrowserSession.open("https://normal.example")
        BrowserSession.save(context)
        assertEquals(1, BrowserSession.savedCount(context))
        AppSettings.set(K.RESTORE_TABS, "never")
        BrowserSession.open("https://next.example")
        BrowserSession.save(context)
        assertEquals(0, JsonStore.readArray(context, "tabs").length())
        assertEquals(0, JsonStore.readArray(context, "tabs_selected").length())
        assertEquals(0, BrowserSession.savedCount(context))
        assertEquals(0, BrowserSession.restore(context))
    }

    @Test fun `selected normal tab survives persistence and restore`() {
        val first = BrowserSession.open("https://first.example")
        BrowserSession.open("https://second.example")
        BrowserSession.select(first.id)
        BrowserSession.save(context)
        BrowserSession.resetForTest()
        BrowserSession.ensureTab()
        assertEquals(2, BrowserSession.restore(context))
        // 退役 ID 不复用：选中项跟随重映射后的新 ID，但仍指向当初选中的标签。
        assertEquals(first.id, JsonStore.readArray(context, "tabs_selected").getLong(0))
        assertEquals("https://first.example", BrowserSession.current()!!.url)
        assertEquals(
            BrowserSession.tabs.first { it.url == "https://first.example" }.id,
            BrowserSession.currentId
        )
        assertNotEquals(first.id, BrowserSession.currentId)
    }

    @Test fun `derived background private tabs never enter saved JSON`() {
        val source = BrowserSession.open("https://private.example/page", incognito = true)
        val child = BrowserSession.openDerived(source.id, "https://private.example/child", selectNew = false)
        BrowserSession.save(context)

        val saved = JsonStore.readArray(context, "tabs")
        assertEquals(0, saved.length())
        assertFalse(saved.toString().contains("private.example"))
        assertTrue(BrowserSession.tabs.first { it.id == child.id }.incognito)
    }

    @Test fun `private URL title and id never enter saved JSON`() {
        BrowserSession.open("https://normal.example", "ordinary")
        BrowserSession.open("https://secret.example", "secret-title", incognito = true)
        BrowserSession.save(context)
        val saved = JsonStore.readArray(context, "tabs")
        assertEquals(1, saved.length())
        assertFalse(saved.toString().contains("secret"))
        val normalId = saved.getJSONObject(0).getLong("id")
        assertEquals(normalId, JsonStore.readArray(context, "tabs_selected").getLong(0))
        BrowserSession.resetForTest()
        assertEquals(1, BrowserSession.restore(context))
        assertEquals(BrowserSession.tabs.single().id, BrowserSession.currentId)
        assertEquals("https://normal.example", BrowserSession.current()!!.url)
    }

    @Test fun `manual mode does not automatically restore but explicit restore works`() {
        BrowserSession.open("https://saved.example")
        BrowserSession.save(context)
        BrowserSession.resetForTest()
        BrowserSession.ensureTab()
        BrowserSession.restoreIfNeeded(context)
        assertTrue(BrowserSession.current()!!.url.isBlank())
        assertEquals(1, BrowserSession.restore(context))
        assertEquals("https://saved.example", BrowserSession.current()!!.url)
    }

    @Test fun `always mode restores automatically and ignores legacy private records`() {
        AppSettings.set(K.RESTORE_TABS, "always")
        JsonStore.writeArray(context, "tabs", JSONArray().apply {
            put(JsonStore.objOf("id" to 4, "url" to "https://secret.example", "incognito" to true))
            put(JsonStore.objOf("id" to 5, "url" to "https://normal.example", "incognito" to false))
        })
        BrowserSession.restoreIfNeeded(context)
        assertEquals(1, BrowserSession.tabs.size)
        assertEquals("https://normal.example", BrowserSession.current()!!.url)
    }

    @Test fun `empty all-private snapshot has no persisted selection`() {
        BrowserSession.open("https://secret.example", incognito = true)
        BrowserSession.save(context)
        assertEquals(0, JsonStore.readArray(context, "tabs").length())
        assertEquals(0, JsonStore.readArray(context, "tabs_selected").length())
    }
}
