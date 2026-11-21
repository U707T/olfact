package android.webkit

import android.content.Context
import android.graphics.Bitmap
import android.util.AttributeSet
import android.view.MotionEvent
import java.io.InputStream

open class WebSettings {
    var javaScriptEnabled = false; var domStorageEnabled = false; var databaseEnabled = false
    var loadWithOverviewMode = false; var useWideViewPort = false; var builtInZoomControls = false
    var displayZoomControls = false; var javaScriptCanOpenWindowsAutomatically = false
    var mediaPlaybackRequiresUserGesture = false; var mixedContentMode = 0; var cacheMode = 0
    var blockNetworkImage = false; var textZoom = 100; var userAgentString = "stub-agent"
    fun setSupportMultipleWindows(enabled: Boolean) {}
    companion object {
        const val MIXED_CONTENT_COMPATIBILITY_MODE = 2; const val LOAD_DEFAULT = -1
        fun getDefaultUserAgent(context: Context) = "stub-agent"
    }
}

open class WebView(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) {
    private val owner = Thread.currentThread()
    private var currentUrl: String? = null
    private val config = WebSettings()
    open val url: String? get() { check(Thread.currentThread() == owner) { "WebView called on wrong thread" }; return currentUrl }
    open val settings: WebSettings get() { check(Thread.currentThread() == owner); return config }
    open val title: String? get() { check(Thread.currentThread() == owner); return "page title" }
    val favicon: Bitmap? = null
    var hitTestResult: HitTestResult? = null
    var isVerticalScrollBarEnabled = false; var isHorizontalScrollBarEnabled = false; var isLongClickable = false
    val evaluatedScripts = ArrayList<String>()
    open fun loadUrl(url: String) { currentUrl = url }
    open fun loadUrl(url: String, additionalHttpHeaders: MutableMap<String, String>) { currentUrl = url }
    open fun onTouchEvent(event: MotionEvent) = false
    open fun destroy() {}
    fun reload() {}; fun stopLoading() {}; fun removeAllViews() {}; fun setBackgroundColor(color: Int) {}
    fun setOnLongClickListener(listener: (WebView) -> Boolean) {}
    fun setDownloadListener(listener: (String, String?, String?, String?, Long) -> Unit) {}
    fun evaluateJavascript(script: String, callback: ((String?) -> Unit)?) { evaluatedScripts += script }
    class HitTestResult(val type: Int, val extra: String?) {
        companion object {
            const val SRC_ANCHOR_TYPE = 7; const val ANCHOR_TYPE = 1; const val SRC_IMAGE_ANCHOR_TYPE = 8
            const val IMAGE_ANCHOR_TYPE = 6; const val IMAGE_TYPE = 5; const val EDIT_TEXT_TYPE = 9
        }
    }
}

interface WebResourceRequest {
    val url: android.net.Uri?
    val isForMainFrame: Boolean
    val requestHeaders: Map<String, String>?
}
class WebResourceError(val errorCode: Int, val description: CharSequence?)
class WebResourceResponse(val mimeType: String?, val encoding: String?, val data: InputStream?) {
    var statusCode = 200
    var responseHeaders: Map<String, String>? = null
    fun setStatusCodeAndReasonPhrase(code: Int, reason: String) { statusCode = code }
}
class RenderProcessGoneDetail
open class WebViewClient {
    open fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = null
    open fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {}
    open fun onPageFinished(view: WebView, url: String) {}
    open fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
    open fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?) = false
    open fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {}
    open fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {}
}
open class WebChromeClient {
    open fun onProgressChanged(view: WebView, newProgress: Int) {}
    open fun onReceivedTitle(view: WebView, title: String?) {}
    open fun onReceivedIcon(view: WebView, icon: Bitmap?) {}
    open fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message) = false
}
class CookieManager private constructor() {
    fun setAcceptThirdPartyCookies(view: WebView, enabled: Boolean) {}
    fun removeAllCookies(callback: ((Boolean) -> Unit)?) { callback?.invoke(true) }
    fun flush() {}; fun getCookie(url: String): String? = null; fun setCookie(url: String, cookie: String) {}
    companion object { private val instance = CookieManager(); fun getInstance() = instance }
}
