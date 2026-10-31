package cn.olfact.web.browser

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import cn.olfact.core.api.AdBlockHook
import cn.olfact.core.api.MediaSniffer
import cn.olfact.core.api.ScriptSource
import cn.olfact.web.doh.SessionSticky
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 跨批依赖注入点。B2 不 import 任何实现，只用 core/api 的接口 + 回调。
 */
class BrowserDependencies(
    val sniffer: MediaSniffer? = null,
    val adblock: AdBlockHook? = null,
    val scripts: ScriptSource? = null,
    val sticky: SessionSticky? = null,
    val cookieJar: CookieJar = CookieJar(),
    val siteConfigStore: SiteConfigStore? = null,
    val uaManager: UaManager? = null,
    val configFor: (String) -> BrowserConfig = { BrowserConfig() },
    val dohPredicate: (String) -> Boolean = { false },
    val events: BrowserEvents = BrowserEvents.NONE,
    val externalUrlHandler: (String) -> Boolean = { false },
    val downloadHandler: (DownloadRequest) -> Unit = {},
    val longPressHandler: (LongPressTarget) -> Unit = {},
    val onActiveTabChanged: (String?) -> Unit = {}
)

/**
 * 标签管理：最多 [maxLiveTabs] 个活 WebView，超出按 LRU 回收（保留 URL，切回时重建）。
 */
class TabManager(
    private val context: Context,
    private val container: ViewGroup? = null,
    private val deps: BrowserDependencies = BrowserDependencies(),
    private val maxLiveTabs: Int = 8,
    private val maxTabs: Int = 24
) {

    class Tab internal constructor(
        val id: String,
        val incognito: Boolean,
        var url: String = "",
        var title: String = "",
        var lastUsed: Long = 0L
    ) {
        var view: BrowserView? = null
            internal set

        val isLive: Boolean get() = view != null
    }

    private val tabs = CopyOnWriteArrayList<Tab>()

    @Volatile
    private var activeId: String? = null

    fun allTabs(): List<Tab> = tabs.toList()

    fun activeTab(): Tab? = tabs.firstOrNull { it.id == activeId }

    fun activeView(): BrowserView? = activeTab()?.view

    fun tabCount(): Int = tabs.size

    fun open(url: String?, incognito: Boolean = false): Tab {
        val tab = Tab(UUID.randomUUID().toString(), incognito, url.orEmpty(), "", System.currentTimeMillis())
        tabs += tab
        if (incognito) deps.cookieJar.enterIncognito()
        trimTabCount(keep = tab)
        activate(tab.id)
        if (!url.isNullOrBlank()) tab.view?.loadUrl(url)
        return tab
    }

    fun activate(id: String): Tab? {
        val tab = tabs.firstOrNull { it.id == id } ?: return null
        activeId = id
        tab.lastUsed = System.currentTimeMillis()
        val view = ensureView(tab)
        attach(view)
        deps.onActiveTabChanged(id)
        return tab
    }

    fun close(id: String) {
        val tab = tabs.firstOrNull { it.id == id } ?: return
        destroyTab(tab)
        tabs.remove(tab)
        deps.sticky?.closeTab(id)
        if (tab.incognito) deps.cookieJar.exitIncognito()
        if (activeId == id) {
            val next = tabs.maxByOrNull { it.lastUsed }
            if (next != null) activate(next.id) else {
                activeId = null
                container?.removeAllViews()
                deps.onActiveTabChanged(null)
            }
        }
    }

    fun closeAll() {
        tabs.toList().forEach { close(it.id) }
    }

    /** 内存吃紧时回收除当前标签外的所有 WebView。 */
    fun trimMemory() {
        tabs.filter { it.id != activeId && it.isLive }
            .sortedBy { it.lastUsed }
            .forEach { hibernate(it) }
    }

    fun saveState(): String {
        val array = JSONArray()
        tabs.forEach { tab ->
            array.put(
                JSONObject().apply {
                    put("id", tab.id)
                    put("url", tab.view?.url ?: tab.url)
                    put("title", tab.title)
                    put("incognito", tab.incognito)
                }
            )
        }
        return JSONObject().apply {
            put("tabs", array)
            put("active", activeId ?: "")
        }.toString()
    }

    /** 只恢复 URL，不恢复表单状态；首屏立即加载，其余懒加载。 */
    fun restoreState(json: String?) {
        if (json.isNullOrBlank()) return
        val restored = runCatching {
            val root = JSONObject(json)
            val array = root.optJSONArray("tabs") ?: JSONArray()
            val list = ArrayList<Tab>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                list += Tab(
                    id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                    incognito = item.optBoolean("incognito"),
                    url = item.optString("url"),
                    title = item.optString("title"),
                    lastUsed = System.currentTimeMillis() - index
                )
            }
            list to root.optString("active")
        }.getOrNull() ?: return

        tabs.clear()
        tabs.addAll(restored.first)
        val active = restored.second?.takeIf { id -> tabs.any { it.id == id } } ?: tabs.firstOrNull()?.id
        active?.let { activate(it) }
    }

    /** WebView 自身的历史快照（表单、滚动位置），可选能力。 */
    fun saveViewState(tabId: String): Bundle? {
        val tab = tabs.firstOrNull { it.id == tabId } ?: return null
        val view = tab.view ?: return null
        return Bundle().also { view.saveState(it) }
    }

    fun restoreViewState(tabId: String, state: Bundle?) {
        if (state == null) return
        val tab = tabs.firstOrNull { it.id == tabId } ?: return
        val view = ensureView(tab)
        view.restoreState(state)
    }

    fun findTabByView(view: WebView): Tab? = tabs.firstOrNull { it.view === view }

    private fun ensureView(tab: Tab): BrowserView {
        tab.view?.let { return it }
        trimLiveTabs(keep = tab)
        val view = createView(tab)
        tab.view = view
        return view
    }

    private fun createView(tab: Tab): BrowserView {
        val host = hostOf(tab.url)
        val siteConfig = deps.siteConfigStore?.configFor(host)
        val baseConfig = deps.configFor(host)
        val merged = siteConfig?.mergeInto(baseConfig) ?: baseConfig
        val config = merged.copy(incognito = tab.incognito)

        val view = BrowserView(context).apply {
            uaManager = deps.uaManager
            applyConfig(config)
        }

        view.onLongPress = { target, _, _ -> deps.longPressHandler(target) }
        view.onDownload = deps.downloadHandler

        view.webViewClient = BrowserClient(
            tabId = tab.id,
            configProvider = { view.config },
            incognito = tab.incognito,
            sniffer = deps.sniffer,
            adblock = deps.adblock,
            sticky = deps.sticky,
            dohPredicate = deps.dohPredicate,
            events = deps.events,
            onPageStarted = { url ->
                tab.url = url
                deps.cookieJar.recordHost(hostOf(url))
                applyScripts(tab, url, view)
            },
            onPageFinished = { url -> tab.url = url },
            onExternalScheme = { url -> deps.externalUrlHandler(url) }
        )
        view.webChromeClient = BrowserChromeClient(
            tabId = tab.id,
            events = deps.events,
            onNewWindow = { open(null, tab.incognito) }
        )
        applyScripts(tab, tab.url, view)
        return view
    }

    private fun applyScripts(tab: Tab, url: String, view: BrowserView) {
        val source = deps.scripts ?: return
        val scripts = runCatching { source.documentStartScripts(url) }.getOrDefault(emptyList())
        view.applyDocumentStartScripts(scripts)
    }

    private fun attach(view: BrowserView) {
        val parent = container ?: return
        if (view.parent === parent) return
        parent.removeAllViews()
        parent.addView(
            view,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }

    private fun trimLiveTabs(keep: Tab) {
        val live = tabs.filter { it.isLive && it.id != keep.id }
        if (live.size + 1 <= maxLiveTabs) return
        live.sortedBy { it.lastUsed }
            .take(live.size + 1 - maxLiveTabs)
            .forEach { hibernate(it) }
    }

    private fun trimTabCount(keep: Tab) {
        if (tabs.size <= maxTabs) return
        tabs.filter { it.id != keep.id }
            .sortedBy { it.lastUsed }
            .take(tabs.size - maxTabs)
            .forEach { close(it.id) }
    }

    /** 回收 WebView 但保留标签条目，切回时重建并恢复 URL。 */
    private fun hibernate(tab: Tab) {
        val view = tab.view ?: return
        tab.url = runCatching { view.url }.getOrNull() ?: tab.url
        container?.removeView(view)
        runCatching { view.destroy() }
        tab.view = null
    }

    private fun destroyTab(tab: Tab) {
        val view = tab.view ?: return
        tab.url = runCatching { view.url }.getOrNull() ?: tab.url
        container?.removeView(view)
        runCatching { view.destroy() }
        tab.view = null
    }

    private fun hostOf(url: String): String =
        runCatching { android.net.Uri.parse(url).host }.getOrNull().orEmpty()
}
