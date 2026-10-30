package cn.olfact.web.browser

import android.webkit.WebView

/**
 * 页内工具：查找、无图、夜间、缩放。挂在当前标签页的 WebView 上。
 */
class PageTools(private val view: WebView) {

    var onFindResult: ((total: Int, current: Int) -> Unit)? = null
        set(value) {
            field = value
            if (value == null) {
                runCatching { view.setFindListener(null) }
            } else {
                runCatching { view.setFindListener { current, total, _ -> value.invoke(total, current) } }
            }
        }

    fun find(text: String, forward: Boolean = true): Boolean {
        if (text.isBlank()) return false
        return runCatching {
            if (forward) view.findAllAsync(text) else view.findNext(false)
            true
        }.getOrDefault(false)
    }

    fun findNext(forward: Boolean = true): Boolean =
        runCatching { view.findNext(forward); true }.getOrDefault(false)

    fun clearFind() {
        runCatching { view.clearMatches() }
    }

    fun setImagesEnabled(enabled: Boolean) {
        (view as? BrowserView)?.setImagesEnabled(enabled) ?: run {
            view.settings.blockNetworkImage = !enabled
            view.reload()
        }
    }

    fun setNightMode(enabled: Boolean) {
        val target = view as? BrowserView
        if (target != null) {
            target.setNightMode(enabled)
        } else {
            runCatching {
                androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(view.settings, enabled)
            }
        }
    }

    fun setTextZoom(zoom: Int) {
        view.settings.textZoom = zoom.coerceIn(50, 200)
    }

    fun desktopMode(enabled: Boolean) {
        val target = view as? BrowserView ?: return
        target.applyConfig(target.config.copy(uaMode = if (enabled) UaMode.DESKTOP else UaMode.MOBILE))
        view.reload()
    }
}
