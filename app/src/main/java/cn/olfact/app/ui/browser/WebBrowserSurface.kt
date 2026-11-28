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
 *
 * **可复用**：`createView` 只建一次 WebView，[view] 把同一个 View 交回给新容器；
 * Fragment 的 View 重建时通过 [attachHost] 换掉宿主回调（旧 host 引用的是已销毁的 View）。
 * 这样「进设置再返回」不会重新加载网页。
 */
class WebBrowserSurface(private val context: Context) : BrowserSurface {

    private val main = Handler(Looper.getMainLooper())
    private var web: BrowserView? = null
    private var chrome: BrowserChromeClient? = null
    private var customViewHost: BrowserSurfaceHost? = null
    private var recoveryContainer: android.view.ViewGroup? = null
    private var lastUrl: String? = null
    private var detectSink: ((String) -> Unit)? = null
    private var mediaSniffer: cn.olfact.core.api.MediaSniffer? = null
    private val sticky = ModuleBackends.sessionSticky

    /** Pending picker callback is independent of the Activity's temporary host detach. */
    private var pendingFileChooserCancellation: (() -> Unit)? = null

    private fun cancelPendingFileChooser() {
        val cancel = pendingFileChooserCancellation
        pendingFileChooserCancellation = null
        cancel?.invoke()
    }

    /** 当前宿主；View 重建后由 [attachHost] 替换。 */
    private var host: BrowserSurfaceHost? = null

    private var tabId: String = UUID.randomUUID().toString()
    private var incognito: Boolean = false

    override fun createView(host: BrowserSurfaceHost): View {
        this.host = host
        // 同一个实例只建一次：第二次调用直接交回原 View。
        web?.let { return it }

        tabId = UUID.randomUUID().toString()
        val tab = BrowserSession.current()
        incognito = tab?.incognito == true
        mediaSniffer = tab?.let { OlfactServices.snifferFactory?.invoke(it.id) }

        val view = BrowserView(context).apply {
            uaManager = UaManager(UaManager.systemMobileUa(context))
            applyConfig(configSnapshot())
        }

        lateinit var client: BrowserClient
        val events = object : BrowserEvents {
            override fun onPageVisited(url: String, title: String) {
                if (!incognito) HistoryStore.record(context, title, url)
            }

            override fun onTitleChanged(tabId: String, title: String) {
                client.updatePageTitle(title)
                this@WebBrowserSurface.host?.onTitle(title)
            }

            override fun onProgress(tabId: String, progress: Int) = this@WebBrowserSurface.host?.onProgress(progress) ?: Unit
        }

        client = BrowserClient(
            tabId = tabId,
            configProvider = { configSnapshot() },
            incognito = incognito,
            sniffer = mediaSniffer,
            adblock = object : cn.olfact.core.api.AdBlockHook {
                override fun shouldBlock(url: String, pageHost: String): Boolean =
                    OlfactServices.adBlock?.shouldBlock(url, pageHost) == true
                override fun cosmeticCss(pageHost: String): String =
                    if (AppSettings.get(K.AD_COSMETIC)) OlfactServices.adBlock?.cosmeticCss(pageHost).orEmpty() else ""
                override fun recordBlocked() { OlfactServices.adBlock?.recordBlocked() }
            },
            sticky = sticky,
            dohPredicate = { hostName -> ModuleBackends.shouldUseDoh(hostName) },
            events = events,
            onPageStarted = { url ->
                chrome?.hideCustomView()
                lastUrl = url
                this@WebBrowserSurface.host?.onPageStarted(url)
                injectScripts(view, url)
            },
            onPageFinished = { url ->
                this@WebBrowserSurface.host?.onPageFinished(url)
                injectLateScripts(view, url)
            },
            onExternalScheme = { url ->
                this@WebBrowserSurface.host != null && cn.olfact.app.data.openExternal(context, url)
            },
            onBeforeNavigate = { url -> injectScripts(view, url) },
            onError = { code, description, url -> this@WebBrowserSurface.host?.onError(code, description, url) ?: Unit },
            onRenderProcessGone = { dead ->
                // 渲染进程被系统杀掉：拆掉这个 WebView，提示用户重新加载，别让整个应用跟着崩
                sticky.closeTab(tabId)
                mediaSniffer?.close()
                mediaSniffer = null
                chrome?.hideCustomView()
                chrome = null
                cancelPendingFileChooser()
                recoveryContainer = dead.parent as? android.view.ViewGroup
                recoveryContainer?.removeView(dead)
                detectSink?.let { InjectionBus.detach(it) }
                detectSink = null
                web = null
                this@WebBrowserSurface.host?.onProgress(100)
                this@WebBrowserSurface.host?.onError(-1, "页面进程已停止，请重试", lastUrl.orEmpty())
            }
        )
        view.webViewClient = client
        val chromeClient = BrowserChromeClient(
            tabId,
            events,
            onChooseFiles = { _, params, callback ->
                val activeHost = this@WebBrowserSurface.host
                if (activeHost == null) {
                    callback.onReceiveValue(null)
                } else {
                    var completed = false
                    var ownCancellation: (() -> Unit)? = null
                    fun complete(uris: List<android.net.Uri>?) {
                        if (completed) return
                        completed = true
                        if (pendingFileChooserCancellation === ownCancellation) {
                            pendingFileChooserCancellation = null
                        }
                        callback.onReceiveValue(uris?.toTypedArray())
                    }
                    val cancelRequest: () -> Unit = {
                        if (!completed) {
                            activeHost.onCancelFileChooser()
                            complete(null)
                        }
                    }
                    ownCancellation = cancelRequest
                    pendingFileChooserCancellation = cancelRequest
                    activeHost.onChooseFiles(
                        mimeTypes = params.acceptTypes.orEmpty().filter { it.isNotBlank() },
                        allowMultiple = params.mode == android.webkit.WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
                    ) { uris -> complete(uris) }
                }
                true
            },
            onShowCustomView = { customView, onExit ->
                val activeHost = this@WebBrowserSurface.host
                // 只有宿主真正接住（当前可见标签）才记住归属；被拒绝时不要留下悬挂的 owner。
                if (activeHost != null && activeHost.onShowCustomView(customView, onExit)) {
                    customViewHost = activeHost
                    true
                } else {
                    false
                }
            },
            onHideCustomView = {
                val owner = customViewHost
                customViewHost = null
                owner?.onHideCustomView()
            }
        )
        chrome = chromeClient
        view.webChromeClient = chromeClient

        view.onLongPress = { target, x, y ->
            this@WebBrowserSurface.host?.onLongPressAt(target.url ?: target.imageUrl, view.title, x, y)
        }
        view.onDownload = { request ->
            // 普通网页下载要使用触发 URL 的 WebView Cookie，登录态资源不能因缺少 Cookie 失败。
            val cookie = runCatching {
                android.webkit.CookieManager.getInstance().getCookie(request.url)
            }.getOrNull()?.takeIf { it.isNotBlank() }
            val headers = cookie?.let { mapOf("Cookie" to it) }.orEmpty()
            // Content-Disposition 里的文件名比 URL 末段靠谱得多（尤其是 /download?id=1 这种）。
            OlfactServices.downloads?.enqueue(
                url = request.url,
                name = DownloadNames.fromContentDisposition(request.contentDisposition),
                pageUrl = request.pageUrl.orEmpty(),
                referer = request.pageUrl.orEmpty(),
                userAgent = request.userAgent,
                mimeType = request.mimeType,
                headers = headers
            )
        }

        val sink: (String) -> Unit = { js -> view.evaluateJavascript(js, null) }
        detectSink = sink
        InjectionBus.attach(sink)

        web = view
        ActiveSurface.attach(this)
        return view
    }

    override fun view(): View? = web

    override fun attachHost(host: BrowserSurfaceHost?) {
        val previousHost = this.host
        // 普通 onPause 只是把宿主摘成 null（进系统文件选择器、来电、切后台），
        // 这种「暂时离开」不能收掉正在播放的网页全屏；只有换成另一个宿主或真正销毁时才收。
        if (previousHost !== host && host != null) chrome?.hideCustomView()
        if (host != null && previousHost != null && previousHost !== host) {
            cancelPendingFileChooser()
        }
        this.host = host
        if (host == null) {
            web?.onPause()
            // 界面已经销毁：断开桥与「当前渲染器」，别让缓存中的 WebView 继续被当作前台页面。
            detectSink?.let { InjectionBus.detach(it) }
            ActiveSurface.clear(this)
            return
        }
        web?.onResume()
        // 重新回到前台：GM 桥与 AI 抓正文都要指向这一份渲染器。
        detectSink?.let { InjectionBus.attach(it) }
        ActiveSurface.attach(this)
    }

    private fun injectScripts(view: WebView, url: String) {
        val scripts = if (AppSettings.get(K.SCRIPT_ENABLED)) {
            OlfactServices.scripts?.documentStartScripts(url).orEmpty()
        } else emptyList()
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
            incognito = incognito,
            blockThirdPartyCookies = AppSettings.get(K.PRIVACY_BLOCK_THIRD_PARTY)
        )
    }

    override fun loadUrl(url: String) {
        chrome?.hideCustomView()
        lastUrl = url
        val view = web ?: recoverView() ?: return
        // 地址栏 / 书签 / 首页搜索都走这里：先注册 document-start 脚本，再加载。
        injectScripts(view, url)
        view.loadUrl(url)
    }

    override fun reload() {
        chrome?.hideCustomView()
        val current = web
        if (current != null) current.reload() else lastUrl?.let(::loadUrl)
    }

    private fun recoverView(): BrowserView? {
        val container = recoveryContainer ?: return null
        val currentHost = host ?: return null
        val view = createView(currentHost)
        recoveryContainer = null
        container.addView(view, 0, android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        ))
        return web
    }

    override fun stopLoading() {
        web?.stopLoading()
    }

    override fun canGoBack(): Boolean = web?.canGoBack() == true

    override fun goBack() {
        chrome?.hideCustomView()
        web?.goBack()
    }

    override fun canGoForward(): Boolean = web?.canGoForward() == true

    override fun goForward() {
        chrome?.hideCustomView()
        web?.goForward()
    }

    override fun clearHistory() {
        web?.clearHistory()
    }

    /**
     * 设置变化后重新读取并应用配置。
     *
     * 之前配置只在 createView 时应用一次：改完「无图 / 夜间 / 字号 / 桌面 UA / JS」
     * 回到浏览器，页面虽然 reload 了，WebView 仍在用旧配置——看起来就是设置没生效。
     */
    override fun refreshConfig() {
        val view = web ?: return
        view.applyConfig(configSnapshot())
        view.applyUserAgent(view.url)
        injectScripts(view, view.url.orEmpty())
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
        chrome?.hideCustomView()
        chrome = null
        cancelPendingFileChooser()
        host = null
        sticky.closeTab(tabId)
        mediaSniffer?.close()
        mediaSniffer = null
        ActiveSurface.clear(this)
        detectSink?.let { InjectionBus.detach(it) }
        detectSink = null
        host = null
        recoveryContainer = null
        web?.destroy()
        web = null
    }
}
