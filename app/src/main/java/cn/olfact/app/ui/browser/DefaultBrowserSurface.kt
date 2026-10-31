package cn.olfact.app.ui.browser

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import cn.olfact.app.data.AppSettings
import cn.olfact.app.data.HistoryStore
import cn.olfact.app.data.K
import cn.olfact.core.api.BrowserSurface
import cn.olfact.core.api.BrowserSurfaceHost

/**
 * 兜底渲染器：一个配置好的 WebView。
 *
 * 这不是 web 模块的替代品——UA、DoH 代理、广告拦截、脚本注入、嗅探都在 B2 的渲染器里。
 * 当 `OlfactServices.browserSurface` 为 null 时（B2 尚未合入），用它保证界面可走通、外链能打开。
 */
class DefaultBrowserSurface(private val context: Context) : BrowserSurface {

    private var web: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun createView(host: BrowserSurfaceHost): View {
        val wv = WebView(context)
        wv.settings.apply {
            javaScriptEnabled = AppSettings.get(K.JS_ENABLED)
            domStorageEnabled = true
            loadsImagesAutomatically = !AppSettings.get(K.NO_IMAGE)
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            textZoom = AppSettings.get(K.TEXT_ZOOM)
            userAgentString = if (AppSettings.get(K.DESKTOP_MODE)) DESKTOP_UA else MOBILE_UA
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(
                view: android.webkit.WebView,
                detail: android.webkit.RenderProcessGoneDetail?
            ): Boolean {
                runCatching { view.destroy() }
                web = null
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                url?.let { host.onPageStarted(it) }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                url?.let {
                    host.onPageFinished(it)
                    HistoryStore.record(context, view?.title.orEmpty(), it)
                }
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                host.onProgress(newProgress)
                if (newProgress > 60) host.onTitle(view?.title)
            }
        }
        wv.setOnLongClickListener {
            host.onLongPress(wv.hitTestResult.extra, wv.title)
            true
        }
        web = wv
        return wv
    }

    override fun loadUrl(url: String) {
        web?.loadUrl(url)
    }

    override fun reload() {
        web?.reload()
    }

    override fun stopLoading() {
        web?.stopLoading()
    }

    override fun canGoBack(): Boolean = web?.canGoBack() == true

    override fun goBack() {
        web?.goBack()
    }

    override fun canGoForward(): Boolean = web?.canGoForward() == true

    override fun goForward() {
        web?.goForward()
    }

    override fun title(): String? = web?.title

    override fun url(): String? = web?.url

    override fun findAll(text: String) {
        web?.findAllAsync(text)
    }

    override fun findNext(forward: Boolean) {
        web?.findNext(forward)
    }

    override fun clearFind() {
        web?.clearMatches()
    }

    override fun destroy() {
        web?.apply {
            stopLoading()
            loadUrl("about:blank")
            destroy()
        }
        web = null
    }

    companion object {
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
}
