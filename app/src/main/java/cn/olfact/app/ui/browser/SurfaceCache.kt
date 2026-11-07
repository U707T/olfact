package cn.olfact.app.ui.browser

import cn.olfact.core.api.BrowserSurface

/**
 * 渲染器复用缓存：最多保留**一份** WebView。
 *
 * 为什么需要：`Nav.push` 用的是 `replace`，进设置/标签页会把 BrowserFragment 的 View
 * 销毁；如果顺手 `destroy()` 掉 WebView，返回时只能重新加载网页（滚动、表单、视频进度全丢）。
 * 这里在 View 销毁时把 WebView 摘下来存一份，重建时按标签 id 取回。
 *
 * 只留一份是刻意的：WebView 内存开销大，多标签都留着很容易 OOM；
 * 常见路径（浏览器 ↔ 设置）只有一份，够用。
 *
 * 线程：所有方法都在主线程（Fragment 生命周期）调用。
 */
internal object SurfaceCache {

    private var tabId: Long = -1L
    private var surface: BrowserSurface? = null

    /** 取出属于 [tabId] 的缓存；不匹配就销毁旧的并返回 null。 */
    fun take(tabId: Long): BrowserSurface? {
        val current = surface ?: return null
        if (this.tabId == tabId && current.view() != null) {
            surface = null
            this.tabId = -1L
            return current
        }
        destroyCurrent()
        return null
    }

    /** 存一份待复用；不支持复用的渲染器直接销毁。 */
    fun offer(tabId: Long, surface: BrowserSurface) {
        if (surface.view() == null) {
            surface.destroy()
            return
        }
        destroyCurrent()
        this.tabId = tabId
        this.surface = surface
    }

    /** Fragment 真被销毁（出栈）时清掉它的缓存，避免留一个后台 WebView。 */
    fun dropTab(tabId: Long) {
        if (this.tabId == tabId) destroyCurrent()
    }

    fun destroyCurrent() {
        surface?.destroy()
        surface = null
        tabId = -1L
    }
}
