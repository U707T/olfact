package cn.olfact.web.browser

import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebView

/** 进度 / 标题 / 图标 / 新窗口请求。 */
class BrowserChromeClient(
    private val tabId: String,
    private val events: BrowserEvents = BrowserEvents.NONE,
    private val onIcon: (Bitmap?) -> Unit = {},
    val onNewWindow: (() -> Unit)? = null,
    private val onChooseFiles: ((WebView, WebChromeClient.FileChooserParams, android.webkit.ValueCallback<Array<android.net.Uri>>) -> Boolean)? = null,
    private val onShowCustomView: ((android.view.View, () -> Unit) -> Boolean)? = null,
    private val onHideCustomView: (() -> Unit)? = null
) : WebChromeClient() {

    private val customViews = CustomViewSession<android.view.View>(
        present = { view, exit -> onShowCustomView?.invoke(view, exit) == true },
        dismiss = { onHideCustomView?.invoke() }
    )

    override fun onShowCustomView(view: android.view.View?, callback: CustomViewCallback) {
        if (view == null) {
            runCatching { callback.onCustomViewHidden() }
            return
        }
        customViews.show(view) { callback.onCustomViewHidden() }
    }

    @Suppress("DEPRECATION")
    override fun onShowCustomView(view: android.view.View?, requestedOrientation: Int, callback: CustomViewCallback) {
        // Keep the user's orientation policy, including for the legacy WebView entrypoint.
        onShowCustomView(view, callback)
    }

    override fun onHideCustomView() { hideCustomView() }

    /** Navigation, host detach, renderer loss and destruction share the same idempotent exit. */
    fun hideCustomView(): Boolean = customViews.hide()

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        events.onProgress(tabId, newProgress)
    }

    override fun onReceivedTitle(view: WebView, title: String?) {
        if (!title.isNullOrBlank()) events.onTitleChanged(tabId, title)
    }

    override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
        onIcon(icon)
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>,
        fileChooserParams: FileChooserParams
    ): Boolean {
        val handler = onChooseFiles
        if (handler == null) {
            filePathCallback.onReceiveValue(null)
            return true
        }
        return runCatching { handler(webView, fileChooserParams, filePathCallback) }
            .getOrElse {
                filePathCallback.onReceiveValue(null)
                true
            }
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
