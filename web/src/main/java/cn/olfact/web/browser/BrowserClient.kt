package cn.olfact.web.browser

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import cn.olfact.core.api.AdBlockHook
import cn.olfact.core.api.MediaItem
import cn.olfact.core.api.MediaSniffer
import cn.olfact.web.doh.SessionSticky
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * 浏览请求链路：拦截 → 嗅探 → 粘性会话打点 → 页面生命周期回调。
 *
 * 三个钩子全部来自 `cn.olfact.core.api`，不 import 任何其他批次的实现。
 */
class BrowserClient(
    private val tabId: String,
    private val configProvider: () -> BrowserConfig,
    private val incognito: Boolean = false,
    private val sniffer: MediaSniffer? = null,
    private val adblock: AdBlockHook? = null,
    private val sticky: SessionSticky? = null,
    private val dohPredicate: (String) -> Boolean = { false },
    private val events: BrowserEvents = BrowserEvents.NONE,
    private val onPageStarted: (String) -> Unit = {},
    private val onPageFinished: (String) -> Unit = {},
    private val onError: (Int, String, String) -> Unit = { _, _, _ -> },
    private val onExternalScheme: (String) -> Boolean = { false },
    /**
     * 主框架即将开始导航（点链接 / JS 跳转）时回调。
     * document-start 脚本必须在文档开始加载**之前**注册，否则只对下一次导航生效。
     */
    private val onBeforeNavigate: (String) -> Unit = {},
    /** 渲染进程被杀（OOM/崩溃）：返回 true 表示我们处理了，避免整个应用被带走。 */
    private val onRenderProcessGone: (WebView) -> Unit = {}
) : WebViewClient() {

    private data class Page(val url: String, val userAgent: String?, val title: String? = null)

    private val blockedCount = AtomicInteger()
    @Volatile private var page = Page("", null)

    /** WebChromeClient supplies title updates on the WebView owner thread. */
    fun updatePageTitle(title: String) {
        page = page.copy(title = title)
    }

    fun blockedRequestCount(): Int = blockedCount.get()

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url ?: return null
        val url = uri.toString()
        val host = uri.host.orEmpty()
        val pageSnapshot = page

        if (host.isNotEmpty()) sticky?.touch(tabId, host)

        val hook = adblock
        if (hook != null && !request.isForMainFrame) {
            val blocked = runCatching { hook.shouldBlock(url, hostOf(pageSnapshot.url)) }.getOrDefault(false)
            if (blocked) {
                blockedCount.incrementAndGet()
                runCatching { hook.recordBlocked() }
                return blockedResponse()
            }
        }

        val mediaType = if (request.method.equals("GET", ignoreCase = true)) MediaTypes.of(url) else null
        val snifferRef = sniffer
        if (mediaType != null && snifferRef != null) {
            val headers = MediaTypes.replayHeaders(request.requestHeaders.orEmpty(), pageSnapshot.userAgent)
            runCatching {
                snifferRef.onMediaDetected(
                    MediaItem(
                        url = url,
                        type = mediaType,
                        pageUrl = if (request.isForMainFrame) url else pageSnapshot.url,
                        pageTitle = pageSnapshot.title.takeUnless { request.isForMainFrame },
                        headers = headers
                    )
                )
            }
        }
        return null
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        page = Page(url, view.settings.userAgentString)
        val host = hostOf(url)
        if (host.isNotEmpty()) {
            sticky?.onMainFrame(tabId, host, dohPredicate(host))
        }
        onPageStarted(url)
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        val host = hostOf(url)
        (view as? BrowserView)?.reapplyPageSettings()
        if (host.isNotEmpty()) {
            val css = runCatching { adblock?.cosmeticCss(host) }.getOrNull().orEmpty()
            if (css.isNotBlank()) (view as? BrowserView)?.injectCss(css)
        }
        if (!incognito) {
            val title = runCatching { view.title }.getOrNull().orEmpty()
            runCatching { events.onPageVisited(url, title) }
        }
        onPageFinished(url)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url ?: return false
        val scheme = uri.scheme?.lowercase()
        if ((scheme == "http" || scheme == "https") && request.isForMainFrame) {
            // 在导航开始前注册 document-start 脚本，避免「点链接进的页面不执行脚本」。
            runCatching { onBeforeNavigate(uri.toString()) }
        }
        return when (scheme) {
            "http", "https", "about", "file", "data", "blob" -> false
            null -> false
            else -> {
                if (request.isForMainFrame) runCatching { onExternalScheme(uri.toString()) }
                true
            }
        }
    }

    override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
        // 不处理的话进程会被直接干掉；这里销毁这个 WebView，让界面自己决定后续（重建/提示）
        runCatching { onRenderProcessGone(view) }
        runCatching { view.destroy() }
        return true
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        super.onReceivedError(view, request, error)
        if (request.isForMainFrame) {
            onError(error.errorCode, error.description?.toString().orEmpty(), request.url?.toString().orEmpty())
        }
    }

    /**
     * HTTP 状态错误不等同于无法渲染网页。保留 WebView 中服务器返回的正文，
     * 让验证码、限流说明和错误页上的恢复链接仍可交互；网络层失败仍走 onReceivedError。
     */
    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse
    ) {
        super.onReceivedHttpError(view, request, errorResponse)
        // Intentionally do not call onError: the response body is still a usable page.
    }

    private fun blockedResponse(): WebResourceResponse {
        val response = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
        runCatching {
            response.setStatusCodeAndReasonPhrase(204, "No Content")
            response.responseHeaders = mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "no-store"
            )
        }
        return response
    }

    private fun hostOf(url: String): String =
        runCatching { Uri.parse(url).host }.getOrNull().orEmpty()

}
