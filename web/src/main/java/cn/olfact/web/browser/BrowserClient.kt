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
import java.util.concurrent.ConcurrentHashMap
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
    private val onExternalScheme: (String) -> Boolean = { false }
) : WebViewClient() {

    private val seenMedia = ConcurrentHashMap<String, Long>()
    private val blockedCount = AtomicInteger()

    fun blockedRequestCount(): Int = blockedCount.get()

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url ?: return null
        val url = uri.toString()
        val host = uri.host.orEmpty()

        if (host.isNotEmpty()) sticky?.touch(tabId, host)

        val hook = adblock
        if (hook != null && !request.isForMainFrame) {
            val blocked = runCatching { hook.shouldBlock(url, host) }.getOrDefault(false)
            if (blocked) {
                blockedCount.incrementAndGet()
                runCatching { hook.recordBlocked() }
                return blockedResponse()
            }
        }

        val mediaType = MediaTypes.of(url)
        val snifferRef = sniffer
        if (mediaType != null && snifferRef != null && !seenRecently(url)) {
            val referer = view.url
            val headers = buildMap {
                referer?.let { put("Referer", it) }
                configProvider().customUa?.let { put("User-Agent", it) }
            }
            runCatching {
                snifferRef.onMediaDetected(
                    MediaItem(
                        url = url,
                        type = mediaType,
                        pageUrl = referer.orEmpty(),
                        pageTitle = runCatching { snifferRef.currentPageTitle() }.getOrDefault(""),
                        headers = headers
                    )
                )
            }
        }
        return null
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
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
        return when (uri.scheme?.lowercase()) {
            "http", "https", "about", "file", "data", "blob" -> false
            null -> false
            else -> onExternalScheme(uri.toString())
        }
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        super.onReceivedError(view, request, error)
        if (request.isForMainFrame) {
            onError(error.errorCode, error.description?.toString().orEmpty(), request.url?.toString().orEmpty())
        }
    }

    private fun seenRecently(url: String): Boolean {
        val now = System.currentTimeMillis()
        val previous = seenMedia.put(url, now)
        if (seenMedia.size > 256) {
            seenMedia.entries.removeAll { now - it.value > SEEN_TTL_MS }
        }
        return previous != null && now - previous < SEEN_TTL_MS
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

    companion object {
        private const val SEEN_TTL_MS = 60_000L
    }
}
