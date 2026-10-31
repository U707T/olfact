package cn.olfact.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
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
 * 注册顺序：先真实现，再由 [LocalBackends] 补空缺（它只在 null 时安装兜底）。
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

    fun install(context: Context) {
        val app = context.applicationContext
        installDoh(app)
        installAdBlock(app)
        installScripts(app)
        installAi(app)
        installDownloads(app)
        installBrowser(app)
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
            emit = { js -> InjectionBus.evaluate(js) }
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
        OlfactServices.downloads = DownloadAdapter(DownloadFacade.get(app), scope)
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

    override fun onMediaDetected(item: cn.olfact.core.api.MediaItem) {
        if (!AppSettings.get(K.SNIFF_ENABLED)) return
        SnifferFeed.add(item)
    }

    override fun currentPageTitle(): String = BrowserSession.current()?.title.orEmpty()

    override fun currentPageUrl(): String = BrowserSession.current()?.url.orEmpty()
}

/** GM_* 桥的 JS 出口：由当前可见的渲染器注入。 */
object InjectionBus {
    @Volatile
    private var sink: ((String) -> Unit)? = null

    fun attach(evaluate: (String) -> Unit) {
        sink = evaluate
    }

    fun detach(evaluate: (String) -> Unit) {
        if (sink === evaluate) sink = null
    }

    fun evaluate(js: String) {
        sink?.invoke(js)
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
        impl.start(
            cn.olfact.web.doh.DohConfig(
                enabled = true,
                mode = mode.toWeb(),
                rules = DnsRules.parse(settings.get(K.DOH_RULES), mode.toWeb()),
                useProxy = true
            )
        )
    }

    fun shouldUseDoh(host: String): Boolean =
        runCatching { impl.rules().useDoh(host) }.getOrDefault(false)

    override fun start(cfg: DohConfig) {
        impl.start(
            cn.olfact.web.doh.DohConfig(
                enabled = cfg.enabled,
                mode = cfg.mode.toWeb(),
                rules = DnsRules.parse(cfg.rules.joinToString("\n"), cfg.mode.toWeb()),
                useProxy = true
            )
        )
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
            upstreams = emptyList(),
            rules = rules.toText().lines().filter { it.isNotBlank() }
        )
    }

    override fun save(cfg: DohConfig) {
        impl.updateRules(DnsRules.parse(cfg.rules.joinToString("\n"), cfg.mode.toWeb()))
        settings.set(K.DOH_MODE, cfg.mode.id)
        if (!cfg.enabled) impl.stop()
    }

    override fun importRules(lines: List<String>) {
        val text = lines.joinToString("\n")
        impl.updateRules(DnsRules.parse(text, impl.rules().mode))
        settings.set(K.DOH_RULES, text)
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
        facade.queue.items.value.map { it.toTask() }

    override fun enqueue(url: String, name: String?, pageUrl: String) {
        val file = name?.ifBlank { null }
            ?: url.substringBefore('?').substringAfterLast('/').ifBlank { "download-${UUID.randomUUID()}" }
        scope.launch {
            runCatching {
                facade.enqueue(
                    DownloadSpec(
                        url = url,
                        fileName = file,
                        pageUrl = pageUrl.ifBlank { null },
                        referer = pageUrl.ifBlank { null }
                    )
                )
            }
        }
    }

    override fun pause(id: String) = facade.pause(id)

    override fun resume(id: String) = facade.resume(id)

    override fun remove(id: String, deleteFile: Boolean) {
        if (deleteFile) facade.remove(id) else facade.cancel(id)
    }

    override fun clearFinished() {
        facade.queue.items.value.filter { it.status.isTerminal }.forEach { facade.remove(it.id) }
    }

    override val changes: Flow<Unit> = facade.queue.items.map { }
}

internal fun cn.olfact.download.DownloadRecord.toTask(): DownloadTask = DownloadTask(
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
    speedBytesPerSec = 0L,
    createdAt = createdAt,
    finishedAt = if (status.isTerminal) updatedAt else 0L,
    pageUrl = spec.pageUrl.orEmpty(),
    savePath = filePath.orEmpty(),
    error = error
)

/** 外部协议（intent://、market://…）交给系统。 */
internal fun openExternal(context: Context, url: String): Boolean = runCatching {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
}.getOrDefault(false)
