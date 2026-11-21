package cn.olfact.app.ui.browser

import android.view.View
import androidx.test.core.app.ApplicationProvider
import cn.olfact.core.api.BrowserSurface
import cn.olfact.core.api.BrowserSurfaceHost
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Shared behavioral contract; the lightweight runner provides only View identity tokens. */
abstract class SurfaceCacheContract {
    protected abstract fun newView(): View

    private class Surface(private val token: View?) : BrowserSurface {
        var destroyed = 0
        var alive = true
        override fun view(): View? = token.takeIf { alive }
        override fun createView(host: BrowserSurfaceHost): View = token!!
        override fun loadUrl(url: String) {}
        override fun reload() {}
        override fun stopLoading() {}
        override fun canGoBack() = false
        override fun goBack() {}
        override fun canGoForward() = false
        override fun goForward() {}
        override fun title(): String? = null
        override fun url(): String? = null
        override fun destroy() { destroyed++; alive = false }
        override fun findAll(text: String) {}
        override fun findNext(forward: Boolean) {}
        override fun clearFind() {}
    }

    @After fun clear() = SurfaceCache.destroyCurrent()

    @Test fun `take returns matching surface once without destroying it`() {
        val surface = Surface(newView())
        SurfaceCache.offer(1, surface)
        assertNull(SurfaceCache.take(2))
        assertSame(surface, SurfaceCache.take(1))
        assertNull(SurfaceCache.take(1))
        assertEquals(0, surface.destroyed)
    }

    @Test fun `fourth detached page evicts only the oldest of three`() {
        val pages = List(4) { Surface(newView()) }
        pages.forEachIndexed { index, page -> SurfaceCache.offer(index.toLong(), page) }
        assertEquals(1, pages[0].destroyed)
        assertNull(SurfaceCache.take(0))
        (1..3).forEach { assertSame(pages[it], SurfaceCache.take(it.toLong())) }
        assertTrue(pages.drop(1).all { it.destroyed == 0 })
    }

    @Test fun `taking and returning a page makes it most recently used`() {
        val pages = List(4) { Surface(newView()) }
        (0..2).forEach { SurfaceCache.offer(it.toLong(), pages[it]) }
        val visited = SurfaceCache.take(0)!!
        SurfaceCache.offer(0, visited)
        SurfaceCache.offer(3, pages[3])
        assertEquals(1, pages[1].destroyed)
        assertEquals(0, pages[0].destroyed)
        assertSame(pages[0], SurfaceCache.take(0))
    }

    @Test fun `replacement destroys old instance but offering same instance is harmless`() {
        val old = Surface(newView())
        val replacement = Surface(newView())
        SurfaceCache.offer(1, old)
        SurfaceCache.offer(1, old)
        assertEquals(0, old.destroyed)
        SurfaceCache.offer(1, replacement)
        assertEquals(1, old.destroyed)
        assertSame(replacement, SurfaceCache.take(1))
        assertEquals(0, replacement.destroyed)
    }

    @Test fun `dead surface is discarded both on offer and on take`() {
        val missing = Surface(null)
        SurfaceCache.offer(1, missing)
        assertEquals(1, missing.destroyed)
        assertNull(SurfaceCache.take(1))
        val crashed = Surface(newView())
        SurfaceCache.offer(2, crashed)
        crashed.alive = false
        assertNull(SurfaceCache.take(2))
        assertEquals(1, crashed.destroyed)
    }

    @Test fun `closing tabs destroys only surfaces no longer retained`() {
        val pages = List(3) { Surface(newView()) }
        pages.forEachIndexed { index, page -> SurfaceCache.offer(index.toLong(), page) }
        SurfaceCache.retainTabs(setOf(1))
        assertEquals(listOf(1, 0, 1), pages.map { it.destroyed })
        SurfaceCache.dropTab(1)
        SurfaceCache.dropTab(1)
        assertEquals(1, pages[1].destroyed)
    }

    @Test fun `destroy all releases each cached page exactly once`() {
        val pages = List(3) { Surface(newView()) }
        pages.forEachIndexed { index, page -> SurfaceCache.offer(index.toLong(), page) }
        SurfaceCache.destroyCurrent()
        SurfaceCache.destroyCurrent()
        assertTrue(pages.all { it.destroyed == 1 })
        (0..2).forEach { assertNull(SurfaceCache.take(it.toLong())) }
    }
}

/** CI executes the same LRU contract using real Android View objects under Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SurfaceCacheTest : SurfaceCacheContract() {
    override fun newView() = View(ApplicationProvider.getApplicationContext())
}
