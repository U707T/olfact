package cn.olfact.web.doh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStickyTest {

    @Test
    fun `主域命中后子域与第三方资源都走 DoH`() {
        val sticky = SessionSticky()
        sticky.onMainFrame("tab1", "https://example.com/a", useDoh = true)

        assertTrue(sticky.isStickyHost("example.com"))
        assertTrue(sticky.isStickyHost("www.example.com"))
        assertTrue(sticky.isStickyHost("cdn.example.com"))

        sticky.touch("tab1", "third-party.cn")
        assertTrue(sticky.isStickyHost("third-party.cn"))
    }

    @Test
    fun `未命中的标签页不会污染粘性表`() {
        val sticky = SessionSticky()
        sticky.onMainFrame("tab1", "www.example.com", useDoh = true)
        sticky.onMainFrame("tab2", "www.baidu.com", useDoh = false)
        sticky.touch("tab2", "cdn.baidu.com")

        assertFalse(sticky.isStickyHost("www.baidu.com"))
        assertFalse(sticky.isStickyHost("cdn.baidu.com"))
    }

    @Test
    fun `条目按 TTL 过期`() {
        var now = 0L
        val sticky = SessionSticky(ttlMs = 1_000, clock = { now })
        sticky.onMainFrame("tab1", "example.com", useDoh = true)
        assertTrue(sticky.isStickyHost("example.com"))

        now = 1_500
        assertFalse(sticky.isStickyHost("example.com"))
    }

    @Test
    fun `关闭标签后不再记录新 host`() {
        val sticky = SessionSticky()
        sticky.onMainFrame("tab1", "example.com", useDoh = true)
        sticky.closeTab("tab1")
        sticky.touch("tab1", "late.com")
        assertFalse(sticky.isStickyHost("late.com"))
    }

    @Test
    fun `主域与子域互相不误判`() {
        val sticky = SessionSticky()
        sticky.onMainFrame("tab1", "example.com", useDoh = true)
        assertFalse(sticky.isStickyHost("notexample.com"))
        assertTrue(sticky.isStickyHost("a.example.com"))
        assertEquals(1, sticky.hostCount())
    }
}
