package cn.olfact.app.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `boundTabId` 在 View 重建时的取值规则。
 *
 * 这个不变式保证「切标签后进设置再返回」仍停在新标签上，而不是退回 BrowserFragment
 * 创建时绑定的那个；同时也是 SurfaceCache 能命中缓存的前提（key 是标签 id）。
 */
class BoundTabIdTest {

    @Test
    fun `首次创建用参数里的标签`() {
        assertEquals(7L, resolveBoundTabId(-1L, 7L))
    }

    @Test
    fun `切换过标签后保留当前绑定`() {
        assertEquals(2L, resolveBoundTabId(2L, 7L))
    }

    @Test
    fun `没有参数时保持原值`() {
        assertEquals(9L, resolveBoundTabId(9L, 0L))
    }
}
