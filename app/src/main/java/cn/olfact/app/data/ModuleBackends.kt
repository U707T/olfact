package cn.olfact.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import cn.olfact.ai.AiFacade
import cn.olfact.core.adblock.AdBlockManager
import cn.olfact.core.api.AdBlockController
import cn.olfact.core.api.AdBlockStats
import cn.olfact.core.api.AdBlockSubscription
import cn.olfact.core.api.AiProvider
import cn.olfact.core.api.BrowserSurfaceFactory
import cn.olfact.core.api.DohConfig
import cn.olfact.core.api.DohController
import cn.olfact.core.api.DohManager
import cn.olfact.core.api.DohMode
import cn.olfact.core.api.DohStatus
import cn.olfact.core.api.DownloadCenter
import cn.olfact.core.api.DownloadState
import cn.olfact.core.api.DownloadTask
import cn.olfact.core.api.OlfactServices
import cn.olfact.core.api.ProviderStore
import cn.olfact.core.api.ScriptSource
import cn.olfact.core.api.ScriptStore
import cn.olfact.core.api.UserScript
import cn.olfact.core.script.GmBridge
import cn.olfact.core.script.GmValueStore
import cn.olfact.core.script.RunAt
import cn.olfact.core.script.ScriptSourceImpl
import cn.olfact.app.ui.browser.WebBrowserSurface
import cn.olfact.app.ui.player.SnifferFeed
import cn.olfact.download.DownloadFacade
import cn.olfact.download.DownloadStatus
import cn.olfact.download.DownloadSpec
import cn.olfact.web.doh.DohManagerImpl
import cn.olfact.web.doh.DnsRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 把 B2/B3/B4 的真实实现接到 B5 界面约定的 `core/api` 接口上。
 *
 * 为什么需要这一层：四个批次是并行开发的，各自按任务书的契约编码，
 * 合并时模型字段并不完全一致（例如 B3 的 `DownloadRecord` vs 面板的 `DownloadTask`、
 * B4 的 `cn.olfact.core.script.UserScript` vs 面板的轻量 `UserScript`）。
 * 适配集中在本文件，界面代码（38 屏）无需改动，模块内部实现也不用迁就 UI。
 *
 * 冷启动策略：只有「首帧就要用」的（嗅探回调、渲染器工厂）同步注册；
 * DoH / 拦截 / 脚本 / AI / 下载 都要读盘（订阅 JSON、脚本索引、队列文件），统一放到
 * 后台线程，Application.onCreate 不再等它们。界面读的是 `OlfactServices`，晚几百毫秒
 * 注册也能正常工作（面板在未注册时会显示「未接入」，下此进入即正常）。
 */
object ModuleBackends {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var doh: DohAdapter? = null

    /** 备份/恢复要拿到**注入用的同一份**仓库实例，否则恢复出来的脚本不会立刻生效。 */
    var scriptStore: cn.olfact.core.script.ScriptStore? = null
        private set

    /** 白名单/订阅的备份恢复后要 `reload()`，所以也留一个引用。 */
    var adBlockManager: AdBlockManager? = null
        private set

    /** 后台初始化是否已完成（诊断页/日志用）。 */
    @Volatile
    var ready: Boolean = false
        private set

    fun install(context: Context) {
        val app = context.applicationContext
        // 纯对象，无 IO：浏览器壳第一帧就要
        installBrowser(app)

        // 其余模块要读盘，挪到后台；失败也不该让进程起不来
        scope.launch {
            val began = System.currentTimeMillis()
            runCatching {
                installDoh(app)
                installAdBlock(app)
                installScripts(app)
                installAi(app)
                installDownloads(app)
            }.onFailure {
                android.util.Log.w("olfact", "模块初始化失败", it)
            }
            ready = true
            android.util.Log.i("olfact", "模块初始化完成，用时 ${System.currentTimeMillis() - began} ms")

            // 备份的数据段依赖脚本仓库与拦截管理器，必须排在它们之后
            runCatching { AppBackup.install(app) }
                .onFailure { android.util.Log.w("olfact", "备份初始化失败", it) }
        }
    }

    // ------------------------------------------------------------------ DoH (B2)

    private fun installDoh(app: Context) {
        if (OlfactServices.doh != null) return
        val manager = DohManagerImpl(app)
        val adapter = DohAdapter(manager, AppSettings.repo)
        doh = adapter
        OlfactServices.doh = adapter
        OlfactServices.dohController = adapter
        if (AppSettings.get(K.DOH_ENABLED)) {
            scope.launch { runCatching { adapter.startFromSettings() } }
        }
    }

    /** B2 渲染器用它判断某个 host 是否该走 DoH（粘性会话决策）。 */
    fun shouldUseDoh(host: String): Boolean = doh?.shouldUseDoh(host) ?: false

    // -------------------------------------------------------------- 拦截 (B4)

    private fun installAdBlock(app: Context) {
        if (OlfactServices.adBlockController != null) return
        val manager = AdBlockManager.get(app)
        adBlockManager = manager
        manager.setEnabled(AppSettings.get(K.AD_ENABLED))
        OlfactServices.adBlock = manager
        OlfactServices.adBlockController = AdBlockAdapter(manager)
    }

    // -------------------------------------------------------------- 脚本 (B4)

    private fun installScripts(app: Context) {
        if (OlfactServices.scriptStore != null && OlfactServices.scripts != null) return
        val store = cn.olfact.core.script.ScriptStore(File(app.filesDir, "userscripts"))
        scriptStore = store
        val bridge = GmBridge(
            values = GmValueStore(File(app.filesDir, "gm")),
            http = GmBridge.defaultClient(),
            emit = { js -> InjectionBus.evaluate(js) },
            gmEnabled = { AppSettings.get(K.SCRIPT_GM_BRIDGE) }
        )
        val source: ScriptSource = ScriptSourceImpl(store, bridge)
        OlfactServices.scripts = source
        OlfactServices.scriptStore = ScriptAdapter(store)
    }

    // ---------------------------------------------------------------- AI (B3)

    private fun installAi(app: Context) {
        if (OlfactServices.aiProviders != null) return
        OlfactServices.aiProviders = AiAdapter(AiFacade.get(app).providers)
    }

    // ------------------------------------------------------------ 下载 (B3)

    private fun installDownloads(app: Context) {
        if (OlfactServices.downloads != null) return
        val facade = DownloadFacade.get(app)
        // 通知点击要能回到应用里的下载详情
        facade.contentActivity = cn.olfact.app.MainActivity::class.java
        OlfactServices.downloads = DownloadAdapter(facade, scope)
    }

    // ------------------------------------------------------------ 浏览 (B2)

    private fun installBrowser(app: Context) {
        if (OlfactServices.browserSurfaceFactory != null) return
        OlfactServices.sniffer = SnifferSink
        OlfactServices.browserSurfaceFactory = BrowserSurfaceFactory { WebBrowserSurface(app) }
    }
}

/** 嗅探回调落点：B2 在 `shouldInterceptRequest` 命中媒体时调用。 */
object SnifferSink : cn.olfact.core.api.MediaSniffer {

    /** 小于这个体积的媒体多半是广告/预览片，默认过滤（可在设置里关）。 */
    private const val SMALL_MEDIA_BYTES = 256L * 1024

    override fun onMediaDetected(item: cn.olfact.core.api.MediaItem) {
        if (!AppSettings.get(K.SNIFF_ENABLED)) return
        if (AppSettings.get(K.SNIFF_FILTER_SMALL)) {
            val length = item.contentLength
            if (length != null && length in 1..SMALL_MEDIA_BYTES) return
        }
        SnifferFeed.add(item)
    }

    override fun currentPageTitle(): String = BrowserSession.current()?.title.orEmpty()

    override fun currentPageUrl(): String = BrowserSession.current()?.url.orEmpty()
}

/** GM_* 桥的 JS 出口：由当前可见的渲染器注入。 */
object InjectionBus {
    @Volatile
    private var sink: ((String) -> Unit)? = null

    /** WebView 只能在 UI 线程操作：所有注入统一从主线程发出。 */
    private val main = Handler(Looper.getMainLooper())

    fun attach(evaluate: (String) -> Unit) {
        sink = evaluate
    }

    fun detach(evaluate: (String) -> Unit) {
        if (sink === evaluate) sink = null
    }

    fun evaluate(js: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            sink?.invoke(js)
        } else {
            // GM_xmlhttpRequest 的回调在 olfact-gm-xhr 线程；直接 evaluate 属于未定义行为。
            main.post { sink?.invoke(js) }
        }
    }
}

// --------------------------------------------------------------------- 适配器

/** `core/api` DoH 契约 → B2 的 `web.doh` 实现。 */
class DohAdapter(
    private val impl: DohManagerImpl,
    private val settings: cn.olfact.core.api.SettingsRepository
) : DohManager, DohController {

    /** 用当前设置里的开关/模式/上游跑一次。 */
    fun startFromSettings() {
        val mode = DohMode.of(settings.get(K.DOH_MODE))
        val url = settings.get(K.DOH_UPSTREAM).trim()
        val preset = presetFor(url)
        val custom = customUpstreams(url)
        impl.start(
            cn.olfact.web.doh.DohConfig(
                enabled = true,
                mode = mode.toWeb(),
                rules = DnsRules.parse(settings.get(K.DOH_RULES), mode.toWeb()),
                useProxy = true,
                preferredUpstreamId = preset?.id,
                customUpstreams = custom,
                // 用户显式填的自定义地址：只用它，不再和内置上游混跑
                customOnly = custom.isNotEmpty()
            )
        )
    }

    /** 当前选中的上游地址（预设或自定义）。 */
    fun selectedUpstreamUrl(): String = settings.get(K.DOH_UPSTREAM).trim()

    /** 命中某个内置上游则返回它；否则视为自定义（auto / 空串返回 null）。 */
    fun presetFor(url: String): cn.olfact.web.doh.DohUpstream? =
        cn.olfact.web.doh.DohUpstream.byUrl(url)

    companion object {
        /** 「自动」：内置上游并发竞速。 */
        const val AUTO = "auto"

        /** 给界面用：判断某个地址是不是内置上游（不需要实例）。 */
        fun isPresetUrl(url: String): Boolean =
            cn.olfact.web.doh.DohUpstream.byUrl(url) != null

        /**
         * 把用户填的地址翻译成池子里的两条上游。
         *
         * 规则（与设置页的校验保持一致）：
         * - 必须 https://
         * - 必须有主机名，且带非空路径（`/dns-query` 这类端点；只有 host 的地址大概率填错）
         * 不合法返回空表 → 退化为只用内置上游，不会把引擎带崩。
         */
        fun buildCustomUpstreams(url: String): List<cn.olfact.web.doh.DohUpstream> {
            val clean = url.trim()
            if (!clean.startsWith("https://", ignoreCase = true)) return emptyList()
            val rest = clean.substringAfter("://")
            val host = rest.substringBefore('/').substringBefore(':')
            val path = rest.substringAfter('/', "")
            if (host.isBlank() || path.isBlank()) return emptyList()
            val label = "自定义 · $host"
            return listOf(
                cn.olfact.web.doh.DohUpstream(
                    id = "custom-cn", label = label, url = clean,
                    region = cn.olfact.web.doh.DohUpstream.Region.DOMESTIC,
                    supportsEcs = false, bootstrap = emptyList()
                ),
                cn.olfact.web.doh.DohUpstream(
                    id = "custom-intl", label = label, url = clean,
                    region = cn.olfact.web.doh.DohUpstream.Region.FOREIGN,
                    supportsEcs = false, bootstrap = emptyList()
                )
            )
        }
    }

    /** 自定义上游：同一地址注册成「国内 / 国外」两条身份，让它在任何分池里都能参选。 */
    fun customUpstreams(url: String): List<cn.olfact.web.doh.DohUpstream> = buildCustomUpstreams(url)

    fun shouldUseDoh(host: String): Boolean =
        runCatching { impl.rules().useDoh(host) }.getOrDefault(false)

    override fun start(cfg: DohConfig) {
        val requested = cfg.upstreams.firstOrNull().orEmpty()
        val preset = presetFor(requested)
        val custom = customUpstreams(requested)
        impl.start(
            cn.olfact.web.doh.DohConfig(
                enabled = cfg.enabled,
                mode = cfg.mode.toWeb(),
                rules = DnsRules.parse(cfg.rules.joinToString("\n"), cfg.mode.toWeb()),
                useProxy = true,
                preferredUpstreamId = preset?.id,
                customUpstreams = custom,
                customOnly = custom.isNotEmpty()
            )
        )
    }

    /**
     * 只切分流模式，规则文本不变：立即重建规则表，不用重启应用。
     * 设置页的三个模式选项之前只写了 DataStore，引擎仍在用旧模式。
     */
    fun applyMode(mode: DohMode) {
        val current = impl.rules()
        impl.updateRules(DnsRules.parse(current.toText(), mode.toWeb()))
        settings.set(K.DOH_MODE, mode.id)
    }

    /** 保存规则文本并立即生效（「保存规则」按钮之前只写 DataStore）。 */
    fun applyRules(text: String, mode: DohMode) {
        impl.updateRules(DnsRules.parse(text, mode.toWeb()))
        settings.set(K.DOH_RULES, text)
        settings.set(K.DOH_MODE, mode.id)
    }

    override fun stop() = impl.stop()

    override fun status(): DohStatus {
        val s = impl.status()
        return DohStatus(
            enabled = s.running,
            mode = s.mode.toCore(),
            upstream = listOfNotNull(s.domesticUpstream, s.foreignUpstream)
                .distinct().joinToString(" / ").ifBlank { "—" },
            avgCostMs = s.avgLatencyMs.toInt(),
            cacheHitRate = (s.cacheHitRate * 100).toInt().coerceIn(0, 100),
            failures = s.failures,
            queriesToday = s.todayQueries,
            proxying = s.proxying,
            lastRaceAt = 0L,
            engineReady = true
        )
    }

    override fun clearCache() = impl.clearCache()

    override fun rerace() = impl.rerace()

    override fun isProxying(): Boolean = impl.isProxying()

    override fun config(): DohConfig {
        val rules = impl.rules()
        return DohConfig(
            enabled = true,
            mode = rules.mode.toCore(),
            upstreams = listOf(selectedUpstreamUrl()),
            rules = rules.toText().lines().filter { it.isNotBlank() }
        )
    }

    override fun save(cfg: DohConfig) {
        // 统一走 applyRules：规则与模式一起落到引擎和 DataStore，避免只写一半。
        applyRules(cfg.rules.joinToString("\n"), cfg.mode)
        if (!cfg.enabled) impl.stop()
    }

    override fun importRules(lines: List<String>) {
        val text = lines.joinToString("\n")
        applyRules(text, impl.rules().mode.toCore())
    }

    override fun exportRules(): String = impl.rules().toText().lines()
        .filter { it.isNotBlank() }.joinToString("\n")
}

private fun DohMode.toWeb(): cn.olfact.web.doh.DohMode = when (this) {
    DohMode.SMART -> cn.olfact.web.doh.DohMode.SMART
    DohMode.INCLUDE -> cn.olfact.web.doh.DohMode.ONLY_LISTED
    DohMode.EXCLUDE -> cn.olfact.web.doh.DohMode.EXCLUDE_LISTED
}

private fun cn.olfact.web.doh.DohMode.toCore(): DohMode = when (this) {
    cn.olfact.web.doh.DohMode.SMART -> DohMode.SMART
    cn.olfact.web.doh.DohMode.ONLY_LISTED -> DohMode.INCLUDE
    cn.olfact.web.doh.DohMode.EXCLUDE_LISTED -> DohMode.EXCLUDE
}

/** `core/api` 拦截面板 → B4 的 `AdBlockManager`。 */
class AdBlockAdapter(private val manager: AdBlockManager) : AdBlockController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun stats(): AdBlockStats {
        val s = manager.state.value
        return AdBlockStats(
            enabled = s.enabled,
            totalBlocked = s.stats.total,
            todayBlocked = s.stats.today,
            ruleCount = s.ruleCount,
            subscriptionCount = s.subscriptions.count { it.enabled },
            lastUpdatedAt = s.lastRefreshAt,
            engineReady = s.ruleCount > 0,
            engineName = s.engineLabel
        )
    }

    override fun setEnabled(enabled: Boolean) = manager.setEnabled(enabled)

    override fun subscriptions(): List<AdBlockSubscription> =
        manager.state.value.subscriptions.map {
            AdBlockSubscription(it.id, it.title, it.url, it.enabled, it.ruleCount)
        }

    override fun setSubscriptionEnabled(id: String, enabled: Boolean) =
        manager.setSubscriptionEnabled(id, enabled)

    override fun addSubscription(name: String, url: String) {
        manager.addSubscription(name, url)
    }

    override fun removeSubscription(id: String) = manager.removeSubscription(id)

    override fun refresh() {
        scope.launch { runCatching { manager.updateAll(force = true) } }
    }

    override fun refreshAsync(onDone: (Boolean, String) -> Unit) {
        scope.launch {
            val result = runCatching { manager.updateAll(force = true) }
            val ruleCount = manager.state.value.ruleCount
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { onDone(true, "规则已更新 · $ruleCount 条") },
                    onFailure = { onDone(false, it.message ?: "更新失败") }
                )
            }
        }
    }

    override fun whitelist(): List<String> = manager.whitelist()

    override fun setWhitelist(hosts: List<String>) {
        val target = hosts.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        manager.whitelist().filterNot { it in target }.forEach { manager.removeWhitelist(it) }
        target.filterNot { manager.isWhitelisted(it) }.forEach { manager.addWhitelist(it) }
    }
}

/** `core/api` 脚本面板 → B4 的 `core.script.ScriptStore`。 */
class ScriptAdapter(
    private val store: cn.olfact.core.script.ScriptStore
) : ScriptStore {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun all(): List<UserScript> = store.list().map { meta ->
        UserScript(
            id = meta.id,
            name = meta.name,
            version = meta.version.ifBlank { "1.0" },
            matches = meta.matches,
            enabled = meta.enabled,
            code = store.body(meta.id).orEmpty(),
            runAtStart = meta.runAt == RunAt.DOCUMENT_START
        )
    }

    override fun count(): Int = store.count()

    override fun save(script: UserScript) {
        val existing = store.find(script.id)
        val meta = cn.olfact.core.script.UserScript(
            id = script.id,
            name = script.name,
            version = script.version,
            matches = script.matches.ifEmpty { listOf("*://*/*") },
            runAt = if (script.runAtStart) RunAt.DOCUMENT_START else RunAt.DOCUMENT_END,
            enabled = script.enabled,
            installedAt = existing?.installedAt ?: 0L
        )
        store.save(meta, script.code)
    }

    override fun remove(id: String) {
        store.delete(id)
    }

    override fun setEnabled(id: String, enabled: Boolean) {
        store.setEnabled(id, enabled)
    }

    override fun installFromUrl(url: String, onDone: (Boolean, String) -> Unit) {
        scope.launch {
            val parsed = store.installFromUrl(url, GmBridge.defaultClient())
            withContext(Dispatchers.Main) {
                if (parsed != null) onDone(true, parsed.name)
                else onDone(false, "下载或解析失败，请确认链接指向 .user.js")
            }
        }
    }
}

/** `core/api` AI 面板 → B3 的 `ProviderStore`（密钥仍走 Keystore）。 */
class AiAdapter(private val store: cn.olfact.ai.ProviderStore) : ProviderStore {

    override fun all(): List<AiProvider> = store.list().map { p ->
        AiProvider(
            id = p.id,
            name = p.name,
            baseUrl = p.baseUrl,
            apiKey = store.apiKey(p).orEmpty(),
            model = p.model,
            enabled = p.enabled
        )
    }

    override fun save(provider: AiProvider) {
        val existing = store.get(provider.id)
        store.upsert(
            cn.olfact.ai.AiProvider(
                id = provider.id,
                name = provider.name,
                baseUrl = provider.baseUrl,
                model = provider.model,
                keyRef = existing?.keyRef ?: "provider_${provider.id}",
                enabled = provider.enabled
            ),
            apiKey = provider.apiKey.ifBlank { null }
        )
    }

    override fun remove(id: String) = store.remove(id)

    override fun setEnabled(id: String, enabled: Boolean) {
        val p = store.get(id) ?: return
        store.upsert(p.copy(enabled = enabled))
    }

    override fun activeId(): String? = store.selectedId()

    override fun setActive(id: String?) {
        if (id == null) return
        store.select(id)
    }
}

/** `core/api` 下载面板 → B3 的 `DownloadFacade`。 */
class DownloadAdapter(
    private val facade: DownloadFacade,
    private val scope: CoroutineScope
) : DownloadCenter {

    override fun tasks(): List<DownloadTask> =
        facade.queue.items.value.map { it.toTask(speed = facade.queue.speedOf(it.id)) }

    override fun enqueue(
        url: String,
        name: String?,
        pageUrl: String,
        referer: String,
        userAgent: String?,
        mimeType: String?,
        headers: Map<String, String>
    ) {
        val file = name?.ifBlank { null }
            ?: url.substringBefore('?').substringAfterLast('/').ifBlank { "download-${UUID.randomUUID()}" }
        scope.launch {
            runCatching {
                facade.enqueue(
                    DownloadSpec(
                        url = url,
                        fileName = file,
                        mimeType = mimeType,
                        userAgent = userAgent,
                        // 页面地址优先当 Referer；没有页面地址时不要瞎填成下载地址本身。
                        referer = referer.ifBlank { pageUrl }.ifBlank { null },
                        pageUrl = pageUrl.ifBlank { null },
                        headers = headers
                    )
                )
            }
        }
    }

    override fun pause(id: String) = facade.pause(id)

    override fun resume(id: String) = facade.resume(id)

    /**
     * 删除记录：删除的是**记录**，文件是否保留由 [deleteFile] 决定。
     * 旧实现把 false 映射成 cancel()，结果记录没删、状态还变成「失败」。
     */
    override fun remove(id: String, deleteFile: Boolean) {
        facade.remove(id, deleteFile)
    }

    /** 「清空已下载」= 只删记录，绝不删用户已经下载好的文件。 */
    override fun clearFinished() {
        facade.queue.items.value.filter { it.status.isTerminal }.forEach {
            facade.remove(it.id, deleteFile = false)
        }
    }

    override val changes: Flow<Unit> = facade.queue.items.map { }
}

internal fun cn.olfact.download.DownloadRecord.toTask(speed: Long = 0L): DownloadTask = DownloadTask(
    id = id,
    name = spec.fileName,
    url = spec.url,
    state = when (status) {
        DownloadStatus.QUEUED -> DownloadState.QUEUED
        DownloadStatus.RUNNING -> DownloadState.RUNNING
        DownloadStatus.PAUSED -> DownloadState.PAUSED
        DownloadStatus.COMPLETED -> DownloadState.DONE
        DownloadStatus.FAILED -> DownloadState.FAILED
        DownloadStatus.CANCELED -> DownloadState.FAILED
    },
    totalBytes = totalBytes.coerceAtLeast(0L),
    doneBytes = bytesDownloaded.coerceAtLeast(0L),
    speedBytesPerSec = speed,
    createdAt = createdAt,
    finishedAt = if (status.isTerminal) updatedAt else 0L,
    pageUrl = spec.pageUrl.orEmpty(),
    savePath = filePath.orEmpty(),
    error = error
)

/** 外部协议（intent://、market://…）交给系统。 */
internal fun openExternal(context: Context, url: String): Boolean {
    // 「允许网页打开应用」关闭时，只放行 http(s)，其它 scheme（intent://、market://…）一律不拉起。
    if (!AppSettings.get(K.ALLOW_OPEN_APP) && !url.startsWith("http://") && !url.startsWith("https://")) {
        return false
    }
    return runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}
