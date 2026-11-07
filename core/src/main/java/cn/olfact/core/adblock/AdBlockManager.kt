package cn.olfact.core.adblock

import android.content.Context
import cn.olfact.core.api.AdBlockHook
import cn.olfact.core.b4.B4Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class AdBlockState(
    val enabled: Boolean = true,
    val engineKind: EngineKind = EngineKind.KOTLIN,
    val nativeAvailable: Boolean = false,
    val nativeError: String? = null,
    val ruleCount: Int = 0,
    val subscriptions: List<Subscription> = emptyList(),
    val whitelist: Set<String> = emptySet(),
    val stats: BlockStatsSnapshot = BlockStatsSnapshot(),
    val lastRefreshAt: Long = 0L,
    val lastError: String? = null
) {
    val engineLabel: String get() = engineKind.displayName
}

class AdBlockConfig(
    val dataDir: File,
    val assetReader: ((String) -> String?)? = null,
    val httpClient: OkHttpClient = SubscriptionUpdater.defaultClient(),
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    val preferNative: Boolean = true,
    val maxKotlinRules: Int = 20_000
)

/**
 * 拦截总装配：订阅 → 规则文本 → 引擎 → [AdBlockHook]。
 *
 * 线程模型：
 * - [engine] 用 `@Volatile` 整体替换，刷新时先建新引擎再换指针，浏览器线程不会看到半成品；
 * - 计数走 [BlockStats]，每 20 次落一次盘；
 * - 状态用 [StateFlow] 暴露给设置页（B5）。
 */
class AdBlockManager(private val config: AdBlockConfig) : AdBlockHook, Closeable {

    private val store = SubscriptionStore(config.dataDir)
    private val whitelist = WhitelistStore(File(config.dataDir, "whitelist.json"))
    private val stats = BlockStats(File(config.dataDir, "stats.json"))
    private val updater = SubscriptionUpdater(store, config.httpClient, config.assetReader)

    private val _state = MutableStateFlow(AdBlockState())
    val state: StateFlow<AdBlockState> = _state.asStateFlow()

    @Volatile
    private var engine: AdBlockEngine? = null

    @Volatile
    private var enabled: Boolean = true

    private val started = AtomicBoolean(false)
    private val lastStatsPublish = AtomicLong(0L)

    /** 上次成功构建/更新规则的时间。开关、白名单变化不能刷新它（设置页会显示「刚刚更新」）。 */
    @Volatile
    private var lastRefreshAt: Long = 0L

    /** 最近一次建引擎的错误（「没有可用规则」等）。开关/白名单变化不该把它抹掉。 */
    @Volatile
    private var lastError: String? = null

    /**
     * 建引擎的串行化闸门。
     *
     * 刷新有 7 个入口（启动、改订阅开关、增删订阅、导入重载、全量更新、单条更新、备份恢复），
     * 不加锁时两条协程会各自建出一个引擎，后完成的那个把先完成的 `close()` 掉，
     * 而浏览器线程可能正在用它 `check()`——use-after-close，轻则漏拦，重则崩在 native 里。
     */
    private val refreshLock = Mutex()

    // ------------------------------------------------------------ lifecycle

    fun start() {
        if (!started.compareAndSet(false, true)) return
        publish()
        config.scope.launch {
            runCatching { updater.ensureBuiltinsInstalled() }
                .onFailure { B4Log.w(TAG, "内置清单安装失败", it) }
            runCatching { refresh() }
                .onFailure { B4Log.e(TAG, "首次建引擎失败", it) }
        }
    }

    /** 重建引擎。订阅变更、规则更新、白名单变更后调用；并发调用会被串行化（见 [refreshLock]）。 */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        refreshLock.withLock { rebuild() }
    }

    private fun rebuild(): Boolean {
        val items = store.items.value
        val sb = StringBuilder(1 shl 19)
        var usedLists = 0
        for (sub in items) {
            if (!sub.enabled) continue
            val body = updater.rulesText(sub)
            if (body.isNullOrBlank()) continue
            sb.append(body).append('\n')
            usedLists++
        }
        val text = sb.toString()
        val created: AdBlockEngine? = if (text.isBlank()) {
            null
        } else {
            try {
                AdBlockEngineFactory.create(
                    rulesText = text,
                    sourceId = "combined($usedLists)",
                    engineCache = File(config.dataDir, "engine.dat"),
                    preferNative = config.preferNative,
                    maxKotlinRules = config.maxKotlinRules
                )
            } catch (t: Throwable) {
                B4Log.e(TAG, "引擎构建失败", t)
                null
            }
        }
        val old = engine
        engine = created
        old?.close()
        if (created != null) lastRefreshAt = System.currentTimeMillis()
        B4Log.d(TAG, "引擎就绪：${created?.kind} / ${created?.ruleCount ?: 0} 条 / $usedLists 个订阅")
        // 错误只在建引擎时更新：之前 publish() 默认把 lastError 清成 null，
        // 用户看到「没有可用规则」后随便拨一下开关，提示就消失了，问题还在。
        lastError = if (created == null) "没有可用规则" else null
        publish()
        return created != null
    }

    override fun close() {
        engine?.close()
        engine = null
        stats.flush()
    }

    // ------------------------------------------------------------- AdBlockHook

    override fun shouldBlock(url: String, pageHost: String): Boolean =
        shouldBlock(url, pageHost, ResourceType.guess(url))

    fun shouldBlock(url: String, pageHost: String, type: ResourceType): Boolean {
        if (!enabled) return false
        if (url.isEmpty()) return false
        val scheme = url.substringBefore(':', "").lowercase()
        if (scheme != "http" && scheme != "https") return false
        if (pageHost.isNotEmpty() && whitelist.contains(pageHost)) return false
        val current = engine ?: return false
        return try {
            current.check(url, pageHost.ifBlank { null }, type)
        } catch (t: Throwable) {
            B4Log.w(TAG, "check 异常：$url", t)
            false
        }
    }

    /**
     * 检查 + 计数一步到位。B2 推荐用这个（`shouldBlock` 只做判断，不计数）。
     */
    fun block(url: String, pageHost: String, type: ResourceType = ResourceType.guess(url)): Boolean {
        val hit = shouldBlock(url, pageHost, type)
        if (hit) {
            stats.record(type)
            publishStatsThrottled()
        }
        return hit
    }

    override fun cosmeticCss(pageHost: String): String {
        if (!enabled || pageHost.isBlank()) return ""
        if (whitelist.contains(pageHost)) return ""
        val selectors = try {
            engine?.cosmeticSelectors(pageHost) ?: emptyList()
        } catch (t: Throwable) {
            B4Log.w(TAG, "cosmetic 异常：$pageHost", t)
            emptyList()
        }
        if (selectors.isEmpty()) return ""
        val clean = ArrayList<String>(selectors.size)
        for (s in selectors) {
            val t = s.trim()
            if (t.isEmpty() || t.length > 400) continue
            if (t.contains('{') || t.contains('}') || t.contains('\n')) continue
            if (!clean.contains(t)) clean.add(t)
            if (clean.size >= MAX_COSMETIC_SELECTORS) break
        }
        if (clean.isEmpty()) return ""
        return buildString(clean.size * 16 + 32) {
            append(clean.joinToString(","))
            append("{display:none!important}")
        }
    }

    override fun recordBlocked() {
        stats.record(ResourceType.OTHER)
        publishStatsThrottled()
    }

    // ---------------------------------------------------------------- 设置项

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        publish()
    }

    fun isEnabled(): Boolean = enabled

    fun isWhitelisted(host: String): Boolean = whitelist.contains(host)

    fun addWhitelist(domain: String): Boolean {
        val ok = whitelist.add(domain)
        if (ok) publish()
        return ok
    }

    fun removeWhitelist(domain: String): Boolean {
        val ok = whitelist.remove(domain)
        if (ok) publish()
        return ok
    }

    fun whitelist(): List<String> = whitelist.all()

    fun setSubscriptionEnabled(id: String, value: Boolean) {
        val changed = store.update(id) { it.copy(enabled = value) } != null
        if (changed) {
            publish()
            config.scope.launch { runCatching { refresh() } }
        }
    }

    fun addSubscription(title: String, url: String): Subscription {
        val id = "custom-" + Integer.toHexString((title + url).hashCode())
        val sub = Subscription(id = id, title = title.ifBlank { url }, url = url.trim(), enabled = true, order = 100)
        store.upsert(sub)
        publish()
        config.scope.launch { runCatching { updateAll(force = false) } }
        return sub
    }

    fun removeSubscription(id: String) {
        if (store.remove(id)) {
            publish()
            config.scope.launch { runCatching { refresh() } }
        }
    }

    /**
     * 从磁盘重读订阅与白名单并重建引擎。
     * 备份导入是另一个 [SubscriptionStore] 实例写的文件，导入完必须调这个，否则内存里还是旧清单。
     */
    suspend fun reload() {
        store.load()
        whitelist.load()
        refresh()
    }

    suspend fun updateAll(force: Boolean = false): SubscriptionUpdater.UpdateSummary {
        val summary = updater.updateAll(force)
        refresh()
        return summary
    }

    suspend fun updateOne(id: String, force: Boolean = true): SubscriptionUpdater.UpdateResult {
        val sub = store.items.value.firstOrNull { it.id == id }
            ?: return SubscriptionUpdater.UpdateResult(error = "订阅不存在")
        val result = updater.update(sub, force)
        refresh()
        return result
    }

    fun flush() {
        stats.flush()
    }

    fun resetStats() {
        stats.reset()
        publish()
    }

    fun refreshStats() {
        publish()
    }

    // ----------------------------------------------------------------- state

    private fun publish() {
        _state.value = AdBlockState(
            enabled = enabled,
            engineKind = engine?.kind ?: EngineKind.KOTLIN,
            nativeAvailable = AdBlockNative.available,
            nativeError = AdBlockNative.loadError,
            ruleCount = engine?.ruleCount ?: 0,
            subscriptions = store.items.value,
            whitelist = whitelist.all().toSet(),
            stats = stats.snapshot(),
            lastRefreshAt = lastRefreshAt,
            lastError = lastError
        )
    }

    private fun publishStatsThrottled() {
        val now = System.currentTimeMillis()
        val last = lastStatsPublish.get()
        if (now - last < STATS_PUBLISH_INTERVAL_MS) return
        if (!lastStatsPublish.compareAndSet(last, now)) return
        val current = _state.value
        _state.value = current.copy(stats = stats.snapshot())
    }

    companion object {
        private const val TAG = "AdBlock"
        private const val MAX_COSMETIC_SELECTORS = 800
        private const val STATS_PUBLISH_INTERVAL_MS = 500L

        @Volatile
        private var instance: AdBlockManager? = null

        /** 进程内单例；B2/B5 都从这里拿。 */
        fun get(context: Context): AdBlockManager = instance ?: synchronized(this) {
            instance ?: create(context).also { instance = it }
        }

        fun create(context: Context): AdBlockManager {
            val app = context.applicationContext
            val assets = app.assets
            val manager = AdBlockManager(
                AdBlockConfig(
                    dataDir = File(app.filesDir, "adblock"),
                    assetReader = { name ->
                        runCatching {
                            assets.open("${SubscriptionCatalog.ASSET_DIR}/$name")
                                .bufferedReader()
                                .use { it.readText() }
                        }.getOrNull()
                    }
                )
            )
            manager.start()
            return manager
        }
    }
}
