package cn.olfact.app.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标签会话的不变式（纯 JVM，不碰 Android 运行时）。
 *
 * 这些规则是「底栏标签数 / 多窗口列表 / 地址栏」三者不再互相矛盾的前提：
 * 1. 会话至少一个标签；
 * 2. 后台打开（selectNew = false）不能抢 currentId；
 * 3. 当前是空白页时，新建 / 外链都复用，不叠空标签。
 */
class BrowserSessionTest {

    @After
    fun tearDown() {
        BrowserSession.resetForTest()
    }

    @Test
    fun `open in background keeps current tab`() {
        val first = BrowserSession.open("https://a.example")
        BrowserSession.open("https://b.example", selectNew = false)
        assertEquals(first.id, BrowserSession.currentId)
        assertEquals(2, BrowserSession.tabs.size)
        assertEquals("https://b.example", BrowserSession.tabs.last().url)
    }

    @Test
    fun `close last tab leaves one blank tab`() {
        val only = BrowserSession.open("https://a.example")
        BrowserSession.close(only.id)
        assertEquals(1, BrowserSession.tabs.size)
        assertTrue(BrowserSession.tabs.single().url.isBlank())
        assertEquals(BrowserSession.tabs.single().id, BrowserSession.currentId)
    }

    @Test
    fun `closeAll keeps a single blank tab`() {
        BrowserSession.open("https://a.example")
        BrowserSession.open("https://b.example")
        BrowserSession.closeAll()
        assertEquals(1, BrowserSession.tabs.size)
        assertTrue(BrowserSession.current()?.url.isNullOrBlank())
    }

    @Test
    fun `openBlank reuses blank current tab`() {
        val blank = BrowserSession.open(null)
        val again = BrowserSession.openBlank()
        assertSame(blank, again)
        assertEquals(1, BrowserSession.tabs.size)
    }

    @Test
    fun `openBlank creates new tab when current has url`() {
        BrowserSession.open("https://a.example")
        val created = BrowserSession.openBlank()
        assertEquals(2, BrowserSession.tabs.size)
        assertEquals(created.id, BrowserSession.currentId)
    }

    @Test
    fun `external url reuses blank current tab`() {
        val blank = BrowserSession.open(null)
        val reused = BrowserSession.openOrReuseBlank("https://c.example")
        assertSame(blank, reused)
        assertEquals("https://c.example", blank.url)
        assertEquals(1, BrowserSession.tabs.size)
    }

    @Test
    fun `openInCurrent navigates without adding tab`() {
        val tab = BrowserSession.open("https://a.example")
        BrowserSession.openInCurrent("https://b.example")
        assertEquals(1, BrowserSession.tabs.size)
        assertEquals("https://b.example", tab.url)
        assertEquals(tab.id, BrowserSession.currentId)
    }

    @Test
    fun `close current selects neighbour`() {
        val a = BrowserSession.open("https://a.example")
        val b = BrowserSession.open("https://b.example")
        val c = BrowserSession.open("https://c.example")
        BrowserSession.select(b.id)
        BrowserSession.close(b.id)
        assertNotEquals(b.id, BrowserSession.currentId)
        assertTrue(BrowserSession.currentId == a.id || BrowserSession.currentId == c.id)
    }

    @Test
    fun `closeOthers keeps only target`() {
        BrowserSession.open("https://a.example")
        val b = BrowserSession.open("https://b.example")
        BrowserSession.open("https://c.example")
        BrowserSession.closeOthers(b.id)
        assertEquals(1, BrowserSession.tabs.size)
        assertEquals(b.id, BrowserSession.currentId)
    }

    @Test
    fun `max tabs drops oldest`() {
        repeat(BrowserSession.MAX_TABS) { i -> BrowserSession.open("https://s$i.example") }
        BrowserSession.open("https://new.example")
        assertEquals(BrowserSession.MAX_TABS, BrowserSession.tabs.size)
        assertEquals("https://new.example", BrowserSession.current()?.url)
        assertTrue(BrowserSession.tabs.none { it.url == "https://s0.example" })
    }

    @Test
    fun `restore replaces startup blank and respects saved selection`() {
        BrowserSession.ensureTab()
        val saved = listOf(Tab(7, "https://a.example"), Tab(8, "https://b.example"))
        assertEquals(2, BrowserSession.mergeRestored(saved, 7))
        assertEquals(listOf(7L, 8L), BrowserSession.tabs.map { it.id })
        assertEquals(7L, BrowserSession.currentId)
        assertTrue(BrowserSession.open("https://next.example").id > 8)
    }

    @Test
    fun `restore preserves active external page when saved id collides`() {
        val external = BrowserSession.open("https://external.example")
        val saved = listOf(Tab(external.id, "https://saved.example"), Tab(9, "https://other.example"))
        assertEquals(2, BrowserSession.mergeRestored(saved, 9))
        assertSame(external, BrowserSession.current())
        assertEquals(3, BrowserSession.tabs.size)
        assertEquals(3, BrowserSession.tabs.map { it.id }.toSet().size)
        assertTrue(BrowserSession.tabs.any { it.url == "https://saved.example" })
        assertEquals(0, BrowserSession.mergeRestored(saved, 9))
        assertEquals(3, BrowserSession.tabs.size)
    }

    @Test
    fun `private active page never absorbs restored normal tab`() {
        val private = BrowserSession.open("https://same.example", incognito = true)
        val saved = listOf(Tab(private.id, "https://same.example"))
        assertEquals(1, BrowserSession.mergeRestored(saved, private.id))
        assertSame(private, BrowserSession.current())
        assertEquals(2, BrowserSession.tabs.size)
        assertEquals(1, BrowserSession.tabs.count { !it.incognito })
        assertEquals(0, BrowserSession.mergeRestored(saved, private.id))
    }

    @Test
    fun `same url from distinct saved tabs is preserved and restore is idempotent`() {
        val saved = listOf(Tab(6, "https://same.example"), Tab(7, "https://same.example"))
        assertEquals(2, BrowserSession.mergeRestored(saved, 6))
        assertEquals(2, BrowserSession.tabs.size)
        assertEquals(0, BrowserSession.mergeRestored(saved, 6))
        assertEquals(6L, BrowserSession.currentId)
    }

    @Test
    fun `legacy private saved tabs are ignored`() {
        val saved = listOf(Tab(4, "https://secret.example", incognito = true), Tab(5, "https://normal.example"))
        assertEquals(1, BrowserSession.mergeRestored(saved, 4))
        assertEquals(5L, BrowserSession.currentId)
        assertTrue(BrowserSession.tabs.none { it.incognito })
    }

    @Test
    fun `merge capacity limit does not evict current page`() {
        val current = BrowserSession.open("https://current.example")
        val saved = (100L..150L).map { Tab(it, "https://saved$it.example") }
        assertEquals(BrowserSession.MAX_TABS - 1, BrowserSession.mergeRestored(saved, 110))
        assertEquals(BrowserSession.MAX_TABS, BrowserSession.tabs.size)
        assertSame(current, BrowserSession.current())
    }

    @Test
    fun `closed or navigated restored page can be restored again`() {
        val external = BrowserSession.open("https://external.example")
        val saved = listOf(Tab(external.id, "https://saved.example"))
        BrowserSession.mergeRestored(saved, external.id)
        val restored = BrowserSession.tabs.first { it.url == "https://saved.example" }
        restored.url = "https://navigated.example"
        assertEquals(1, BrowserSession.mergeRestored(saved, external.id))
        BrowserSession.close(BrowserSession.tabs.first { it.url == "https://saved.example" }.id)
        assertEquals(1, BrowserSession.mergeRestored(saved, external.id))
        assertSame(external, BrowserSession.current())
    }

    @Test
    fun `invalid saved ids receive unique ids and do not mutate source tabs`() {
        val saved = listOf(Tab(0, "https://zero.example"), Tab(-1, "https://negative.example"), Tab(Long.MAX_VALUE, "https://large.example"))
        assertEquals(3, BrowserSession.mergeRestored(saved, null))
        assertTrue(BrowserSession.tabs.all { it.id > 0 })
        assertEquals(3, BrowserSession.tabs.map { it.id }.distinct().size)
        assertEquals(listOf(0L, -1L, Long.MAX_VALUE), saved.map { it.id })
        val next = BrowserSession.open("https://next.example")
        assertEquals(BrowserSession.tabs.size, BrowserSession.tabs.map { it.id }.distinct().size)
        assertTrue(next.id > 0)
    }

    @Test
    fun `incognito tabs are never persisted`() {
        BrowserSession.open("https://normal.example")
        BrowserSession.open("https://secret.example", incognito = true)

        val persisted = BrowserSession.persistable()
        assertEquals(1, persisted.size)
        assertEquals("https://normal.example", persisted.single().url)
        assertTrue(persisted.none { it.incognito })
    }
}
