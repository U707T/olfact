package cn.olfact.app.ui.browser

import cn.olfact.core.api.BrowserSurface

/**
 * Fragment 的 View 重建时，`boundTabId` 用哪个值。
 *
 * - 首次创建（[current] < 0）：用 Fragment 参数里的标签 id；
 * - 之后（`showTab()` 已经切过标签）：保留当前绑定，**不能**退回参数里的那个，
 *   否则「切到标签 2 → 进设置 → 返回」会跳回标签 1，还会让 SurfaceCache 因 id 不匹配
 *   把标签 2 的 WebView 销毁重建。
 */
internal fun resolveBoundTabId(current: Long, argument: Long): Long =
    if (current < 0L) argument else current

/** Main-thread-only LRU: three detached pages plus the foreground page at most. */
internal object SurfaceCache {
    internal const val CAPACITY = 3
    private val surfaces = linkedMapOf<Long, BrowserSurface>()

    fun take(tabId: Long): BrowserSurface? {
        val cached = surfaces.remove(tabId) ?: return null
        if (cached.view() != null) return cached
        cached.destroy()
        return null
    }

    fun offer(tabId: Long, surface: BrowserSurface) {
        val previous = surfaces.remove(tabId)
        if (previous !== surface) previous?.destroy()
        if (surface.view() == null) {
            surface.destroy()
            return
        }
        surfaces[tabId] = surface
        while (surfaces.size > CAPACITY) dropTab(surfaces.keys.first())
    }

    fun dropTab(tabId: Long) {
        surfaces.remove(tabId)?.destroy()
    }

    fun retainTabs(ids: Set<Long>) {
        surfaces.keys.filterNot { it in ids }.forEach(::dropTab)
    }

    fun destroyCurrent() {
        val cached = surfaces.values.toList()
        surfaces.clear()
        cached.forEach { it.destroy() }
    }
}
