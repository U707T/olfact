package cn.olfact.web.browser

import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebView

/** 进度 / 标题 / 图标 / 新窗口请求。 */
class BrowserChromeClient(
    private val tabId: String,
    private val events: BrowserEvents = BrowserEvents.NONE,
    private val onIcon: (Bitmap?) -> Unit = {},
    val onNewWindow: (() -> Unit)? = null
) : WebChromeClient() {

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        events.onProgress(tabId, newProgress)
    }

    override fun onReceivedTitle(view: WebView, title: String?) {
        if (!title.isNullOrBlank()) events.onTitleChanged(tabId, title)
    }

    override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
        onIcon(icon)
    }

    /** target=_blank：交给外层开新标签（返回 false 时 WebView 自己处理，会丢标签）。 */
    override fun onCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: android.os.Message
    ): Boolean {
        val handler = onNewWindow ?: return false
        handler()
        return false
    }
}
