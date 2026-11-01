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
}
