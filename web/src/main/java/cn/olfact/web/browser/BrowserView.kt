package cn.olfact.web.browser

import android.content.Context
import android.graphics.Bitmap
import android.util.AttributeSet
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * 封装好的 WebView：UA / 缩放 / 无图 / 夜间 / 长按 / 下载 / 脚本注入。
 *
 * 刻意不做任何跨模块耦合——嗅探、拦截、脚本都通过 [BrowserClient] 注入的接口回调，
 * 这里只关心“这一块 WebView 该怎么表现”。
 */
class BrowserView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    var config: BrowserConfig = BrowserConfig()
        private set

    var uaManager: UaManager? = null
        set(value) {
            field = value
            applyUserAgent(url)
        }

    var onLongPress: ((LongPressTarget, Float, Float) -> Unit)? = null
    var onDownload: ((DownloadRequest) -> Unit)? = null
    var onSslError: ((String) -> Unit)? = null

    private val scriptHandles = ArrayList<ScriptHandler>()
    private var nightModeApplied = false
    private var lastUrl: String? = null

    /** 最近一次 ACTION_DOWN 的 View 坐标：长按菜单用它定位。 */
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    init {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            setSupportMultipleWindows(false)
        }
        isVerticalScrollBarEnabled = true
        isHorizontalScrollBarEnabled = false
        isLongClickable = true
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setOnLongClickListener {
            val target = LongPressTarget.from(hitTestResult)
            if (target == null || target.kind == LongPressTarget.Kind.TEXT || target.kind == LongPressTarget.Kind.OTHER) {
                false
            } else {
                onLongPress?.invoke(target, lastTouchX, lastTouchY)
                true
            }
        }
        setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            onDownload?.invoke(
                DownloadRequest(
                    url = url,
                    userAgent = userAgent,
                    contentDisposition = contentDisposition,
                    mimeType = mimeType,
                    contentLength = contentLength,
                    // 页面地址 = 当前正在看的页面，不是下载地址本身：
                    // 下载器要拿它当 Referer，填成下载地址会让防盗链站点直接 403。
                    pageUrl = this@BrowserView.url ?: lastUrl ?: url
                )
            )
        }
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            lastTouchX = event.x
            lastTouchY = event.y
        }
        return super.onTouchEvent(event)
    }

    fun applyConfig(newConfig: BrowserConfig) {
        config = newConfig
        settings.apply {
            javaScriptEnabled = newConfig.javaScriptEnabled
            blockNetworkImage = !newConfig.imagesEnabled
            textZoom = newConfig.textZoom.coerceIn(50, 200)
        }
        // 第三方 Cookie 是 WebView 级开关；无痕模式默认也禁。
        runCatching {
            android.webkit.CookieManager.getInstance()
                .setAcceptThirdPartyCookies(this, !newConfig.blockThirdPartyCookies && !newConfig.incognito)
        }
        applyUserAgent(url)
        setNightMode(newConfig.nightMode)
    }

    fun applyUserAgent(url: String?) {
        val manager = uaManager ?: return
        val host = hostOf(url)
        settings.userAgentString = manager.uaFor(host, config.uaMode, config.customUa)
    }

    fun setImagesEnabled(enabled: Boolean, reload: Boolean = true) {
        config = config.copy(imagesEnabled = enabled)
        settings.blockNetworkImage = !enabled
        if (reload) reload()
    }

    fun setNightMode(enabled: Boolean) {
        config = config.copy(nightMode = enabled)
        nightModeApplied = enabled
        val algorithmic = WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)
        if (algorithmic) {
            runCatching { WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, enabled) }
            if (!enabled) removeNightCss()
        } else {
            injectNightCss(enabled)
        }
    }

    /** 页面加载完成后补齐需要 DOM 才能生效的设置。 */
    fun reapplyPageSettings() {
        if (nightModeApplied && !WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            injectNightCss(true)
        }
        settings.textZoom = config.textZoom.coerceIn(50, 200)
    }

    /** 文档开始前注入脚本；重复调用会先撤销旧的，避免叠 N 份。 */
    fun applyDocumentStartScripts(scripts: List<String>) {
        scriptHandles.forEach { handle -> runCatching { handle.remove() } }
        scriptHandles.clear()
        if (scripts.isEmpty()) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        scripts.forEach { source ->
            runCatching {
                scriptHandles += WebViewCompat.addDocumentStartJavaScript(this, source, setOf("*"))
            }
        }
    }

    fun injectCss(css: String) {
        if (css.isBlank()) return
        val quoted = JSONObject.quote(css)
        evaluateJavascript(
            "(function(){var id='olfact-cosmetic';var s=document.getElementById(id);" +
                "if(!s){s=document.createElement('style');s.id=id;(document.head||document.documentElement).appendChild(s);}" +
                "s.textContent=$quoted;})();",
            null
        )
    }

    override fun loadUrl(url: String) {
        lastUrl = url
        applyUserAgent(url)
        super.loadUrl(url)
    }

    override fun loadUrl(url: String, additionalHttpHeaders: MutableMap<String, String>) {
        lastUrl = url
        applyUserAgent(url)
        super.loadUrl(url, additionalHttpHeaders)
    }

    override fun destroy() {
        scriptHandles.forEach { handle -> runCatching { handle.remove() } }
        scriptHandles.clear()
        runCatching { stopLoading() }
        runCatching { loadUrl("about:blank") }
        removeAllViews()
        super.destroy()
    }

    private fun injectNightCss(enabled: Boolean) {
        if (enabled) {
            injectCss(
                "html{filter:invert(1) hue-rotate(180deg)!important;background:#111!important}" +
                    "img,video,picture,canvas,svg,iframe{filter:invert(1) hue-rotate(180deg)!important}"
            )
        } else {
            evaluateJavascript(
                "(function(){var s=document.getElementById('olfact-cosmetic');if(s)s.remove();})();",
                null
            )
        }
    }

    private fun removeNightCss() {
        evaluateJavascript(
            "(function(){var s=document.getElementById('olfact-cosmetic');if(s)s.remove();})();",
            null
        )
    }

    private fun hostOf(url: String?): String? =
        url?.let { runCatching { android.net.Uri.parse(it).host }.getOrNull() }

    fun favicon(): Bitmap? = runCatching { favicon }.getOrNull()
}
