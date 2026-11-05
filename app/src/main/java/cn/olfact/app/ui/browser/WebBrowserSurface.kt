package cn.olfact.app.ui.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import cn.olfact.app.data.ActiveSurface
import cn.olfact.app.data.AppSettings
import cn.olfact.app.data.BrowserSession
import cn.olfact.app.data.HistoryStore
import cn.olfact.app.data.InjectionBus
import cn.olfact.app.data.K
import cn.olfact.app.data.ModuleBackends
import cn.olfact.core.api.BrowserSurface
import cn.olfact.core.api.BrowserSurfaceHost
import cn.olfact.core.api.OlfactServices
import cn.olfact.core.script.RunAt
import cn.olfact.core.script.ScriptSourceImpl
import cn.olfact.web.browser.BrowserChromeClient
import cn.olfact.web.browser.BrowserClient
import cn.olfact.web.browser.BrowserConfig
import cn.olfact.web.browser.BrowserEvents
import cn.olfact.web.browser.BrowserView
import cn.olfact.web.browser.DownloadNames
import cn.olfact.web.browser.UaManager
import cn.olfact.web.browser.UaMode
import cn.olfact.web.doh.SessionSticky
import java.util.UUID

/**
 * B2 渲染器接到 [BrowserSurface] 上的适配器：一个实例 = 一个标签页。
 *
 * 与 [DefaultBrowserSurface] 的区别：这里的 WebView 是 B2 的 `BrowserView`，
 * 走拦截 → 嗅探 → DoH 粘性 → 脚本注入的完整链路；兜底类只在 web 模块缺失时使用。
 */
class WebBrowserSurface(private val context: Context) : BrowserSurface {

    private val main = Handler(Looper.getMainLooper())
    private var web: BrowserView? = null
    private var detectSink: ((String) -> Unit)? = null
    private val sticky = SessionSticky()

    override fun createView(host: BrowserSurfaceHost): View {
        val tabId = UUID.randomUUID().toString()
        val incognito = BrowserSession.current()?.incognito == true

        val view = BrowserView(context).apply {
            uaManager = UaManager(UaManager.systemMobileUa(context))
            applyConfig(configSnapshot())
        }

        val events = object : BrowserEvents {
            override fun onPageVisited(url: String, title: String) {
                if (!incognito) HistoryStore.record(context, title, url)
            }

            override fun onTitleChanged(tabId: String, title: String) = host.onTitle(title)

            override fun onProgress(tabId: String, progress: Int) = host.onProgress(progress)
        }

        view.webViewClient = BrowserClient(
            tabId = tabId,
            configProvider = { configSnapshot() },
            incognito = incognito,
            sniffer = OlfactServices.sniffer,
            adblock = OlfactServices.adBlock,
            sticky = sticky,
            dohPredicate = { hostName -> ModuleBackends.shouldUseDoh(hostName) },
            events = events,
            onPageStarted = { url ->
                host.onPageStarted(url)
                injectScripts(view, url)
            },
            onPageFinished = { url ->
                host.onPageFinished(url)
                injectLateScripts(view, url)
            },
            onExternalScheme = { url -> cn.olfact.app.data.openExternal(context, url) },
            onError = { code, description, url -> host.onError(code, description, url) },
            onRenderProcessGone = { dead ->
                // 渲染进程被系统杀掉：拆掉这个 WebView，提示用户重新加载，别让整个应用跟着崩
                (dead.parent as? android.view.ViewGroup)?.removeView(dead)
                web = null
                android.widget.Toast.makeText(context, "页面进程已重启，请重新加载", android.widget.Toast.LENGTH_SHORT).show()
                host.onPageFinished("")
            }
        )
        view.webChromeClient = BrowserChromeClient(tabId, events)

        view.onLongPress = { target, x, y ->
            host.onLongPressAt(target.url ?: target.imageUrl, view.title, x, y)
        }
        view.onDownload = { request ->
            // Content-Disposition 里的文件名比 URL 末段靠谱得多（尤其是 /download?id=1 这种）。
            OlfactServices.downloads?.enqueue(
                url = request.url,
                name = DownloadNames.fromContentDisposition(request.contentDisposition),
                pageUrl = request.pageUrl.orEmpty(),
                referer = request.pageUrl.orEmpty(),
                userAgent = request.userAgent,
                mimeType = request.mimeType
            )
        }

        val sink: (String) -> Unit = { js -> view.evaluateJavascript(js, null) }
        detectSink = sink
        InjectionBus.attach(sink)

        web = view
        ActiveSurface.attach(this)
        return view
    }

    private fun injectScripts(view: WebView, url: String) {
        if (!AppSettings.get(K.SCRIPT_ENABLED)) return
        val scripts = OlfactServices.scripts?.documentStartScripts(url).orEmpty()
        (view as? BrowserView)?.applyDocumentStartScripts(scripts)
    }

    /** document-end 阶段：`addDocumentStartJavaScript` 只覆盖文档开始，其余阶段要显式注入。 */
    private fun injectLateScripts(view: WebView, url: String) {
        if (!AppSettings.get(K.SCRIPT_ENABLED)) return
        val source = OlfactServices.scripts as? ScriptSourceImpl ?: return
        runCatching {
            source.scriptsFor(url, RunAt.DOCUMENT_END).forEach { view.evaluateJavascript(it.js, null) }
            source.scriptsFor(url, RunAt.DOCUMENT_IDLE).forEach { view.evaluateJavascript(it.js, null) }
        }
    }

    private fun configSnapshot(): BrowserConfig {
        val night = when (AppSettings.get(K.NIGHT_WEB)) {
            "on" -> true
            "off" -> false
            else -> (context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        val customUa = AppSettings.get(K.WEB_CUSTOM_UA).trim().ifBlank { null }
        return BrowserConfig(
            uaMode = when {
                customUa != null -> UaMode.CUSTOM
                AppSettings.get(K.DESKTOP_MODE) -> UaMode.DESKTOP
                else -> UaMode.MOBILE
            },
            customUa = customUa,
            imagesEnabled = !AppSettings.get(K.NO_IMAGE),
            javaScriptEnabled = AppSettings.get(K.JS_ENABLED),
            nightMode = night,
            textZoom = AppSettings.get(K.TEXT_ZOOM),
            incognito = BrowserSession.current()?.incognito == true,
            blockThirdPartyCookies = AppSettings.get(K.PRIVACY_BLOCK_THIRD_PARTY)
        )
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

    override fun clearHistory() {
        web?.clearHistory()
    }

    override fun title(): String? = web?.title

    override fun url(): String? = web?.url

    override fun evaluateJavascript(script: String, callback: (String) -> Unit) {
        val run = Runnable {
            val view = web ?: run { callback(""); return@Runnable }
            runCatching { view.evaluateJavascript(script) { value -> callback(value.orEmpty()) } }
                .onFailure { callback("") }
        }
        // 调用方可能来自 GM 桥 / AI 的后台线程；WebView 只接受主线程调用。
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else main.post(run)
    }

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
        ActiveSurface.clear(this)
        detectSink?.let { InjectionBus.detach(it) }
        detectSink = null
        web?.destroy()
        web = null
    }
}
