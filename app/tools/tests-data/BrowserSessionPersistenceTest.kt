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
        assertEquals(first.id, BrowserSession.currentId)
        assertEquals("https://first.example", BrowserSession.current()!!.url)
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
        assertEquals(normalId, BrowserSession.currentId)
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
