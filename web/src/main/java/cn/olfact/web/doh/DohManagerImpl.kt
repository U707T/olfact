package cn.olfact.web.doh

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Socket

/**
 * DoH 总装：L1（OkHttp Dns 分流）+ L2（本地隧道 + ProxyController）+ 竞速/缓存/熔断/切网重建。
 *
 * 生命周期建议放在 Application 或浏览器进程级单例；[start] 幂等，重复调用会先 [stop]。
 */
class DohManagerImpl(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val sticky: SessionSticky = SessionSticky(),
    private val cache: DnsCache = DnsCache(),
    private val stats: DohStats = DohStats()
) : DohManager {

    private val _status = MutableStateFlow(DohStatus.idle())
    override val statusFlow: StateFlow<DohStatus> = _status.asStateFlow()

    @Volatile
    private var config: DohConfig = DohConfig()

    @Volatile
    private var currentRules: DnsRules = config.rules

    @Volatile
    private var running: Boolean = false

    @Volatile
    private var lastError: String? = null

    private var transport: DohTransport? = null
    private var pool: UpstreamPool? = null
    private var dohDns: DohDns? = null
    private var routing: RoutingDns? = null
    @Volatile
    private var tunnel: TunnelProxy? = null
    private var watcher: NetworkWatcher? = null
    private var watchdog: Job? = null

    private val bridge = ProxyBridge(
        threshold = 3,
        onCircuitOpen = { reason ->
            lastError = reason
            publish()
        },
        onStateChanged = { publish() }
    )

    override fun start(cfg: DohConfig) {
        if (!cfg.enabled) {
            stop()
            return
        }
        stop()
        config = cfg
        currentRules = cfg.rules
        lastError = null

        val poolUpstreams = when {
            cfg.customOnly && cfg.customUpstreams.isNotEmpty() -> cfg.customUpstreams
            cfg.customUpstreams.isEmpty() -> DohUpstream.DEFAULTS
            else -> DohUpstream.DEFAULTS.filterNot { base -> cfg.customUpstreams.any { it.host == base.host } } +
                cfg.customUpstreams
        }
        val newTransport = DohTransport(
            timeoutMs = cfg.timeoutMs,
            bootstrap = DohUpstream.bootstrapTable(poolUpstreams)
        )
        transport = newTransport
        val newPool = UpstreamPool(
            transport = newTransport,
            upstreams = poolUpstreams,
            scope = scope,
            onEvent = { message ->
                lastError = null
                publish()
                onLog?.invoke(message)
            },
            preferredId = cfg.preferredUpstreamId,
            failureThreshold = cfg.circuitBreakerThreshold
        )
        pool = newPool
        val resolverDns = DohDns(newPool, newTransport, cache, stats, { config })
        dohDns = resolverDns
        routing = RoutingDns(
            rulesProvider = { currentRules },
            doh = resolverDns,
            system = Dns.SYSTEM,
            cache = cache,
            sticky = sticky::isStickyHost
        )

        running = true
        newPool.reraceAsync()
        if (cfg.useProxy) {
            // 隧道启动要做自检（一次真实 CONNECT），不能在主线程做
            scope.launch {
                startTunnel()
                startWatchdog()
                publish()
            }
        }
        watcher = NetworkWatcher(context, onChanged = { reason -> onNetworkChanged(reason) }).also { it.start() }
        publish()
    }

    override fun stop() {
        watchdog?.cancel()
        watchdog = null
        bridge.detach()
        tunnel?.stop()
        tunnel = null
        watcher?.stop()
        watcher = null
        dohDns?.shutdown()
        dohDns = null
        routing = null
        pool = null
        transport = null
        running = false
        publish()
    }

    override fun status(): DohStatus {
        val snapshot = stats.snapshot()
        return DohStatus(
            running = running,
            proxying = isProxying(),
            mode = currentRules.mode,
            domesticUpstream = pool?.currentDomestic()?.label,
            foreignUpstream = pool?.currentForeign()?.label,
            avgLatencyMs = snapshot.avgLatencyMs,
            cacheHitRate = snapshot.cacheHitRate,
            failures = snapshot.failures,
            todayQueries = snapshot.todayQueries,
            ruleCount = currentRules.rules.size,
            consecutiveFailures = bridge.consecutiveFailures(),
            lastError = lastError
        )
    }

    override fun clearCache() {
        cache.clear(DnsCache.PREFIX_DOH)
        cache.clear(DnsCache.PREFIX_SYSTEM)
        publish()
    }

    override fun rerace() {
        pool?.reraceAsync()
    }

    override fun isProxying(): Boolean = running && bridge.isAttached() && tunnel?.isRunning() == true

    override fun rules(): DnsRules = currentRules

    override fun enableForHost(host: String): DnsRules {
        val updated = currentRules.plus(host)
        currentRules = updated
        clearCache()
        publish()
        return updated
    }

    override fun updateRules(rules: DnsRules) {
        currentRules = rules
        clearCache()
        publish()
    }

    override fun appDns(): Dns? = routing

    override fun wrap(client: OkHttpClient): OkHttpClient {
        val dns = routing ?: return client
        return client.newBuilder().dns(dns).build()
    }

    /** 开关面板/设置页更新统计用。 */
    fun refresh() = publish()

    /** 渲染层（B5）把地址栏图标与长按菜单接进来。 */
    fun explain(host: String): String = currentRules.explain(host)

    fun statsSnapshot(): DohStats.Snapshot = stats.snapshot()

    fun tunnelStats(): TunnelProxy.Stats? = tunnel?.stats()

    var onLog: ((String) -> Unit)? = null

    private fun startTunnel() {
        if (!running) return
        val cfg = config
        val resolver: (String) -> List<java.net.InetAddress> = { host ->
            routing?.lookup(host) ?: Dns.SYSTEM.lookup(host)
        }
        val newTunnel = TunnelProxy(
            resolver = resolver,
            onEvent = { message -> onLog?.invoke(message) }
        )
        val port = runCatching { newTunnel.start(cfg.proxyPort) }.getOrElse { error ->
            lastError = "隧道启动失败：${error.message}"
            onLog?.invoke(lastError!!)
            return
        }
        if (!running) {
            newTunnel.stop()
            return
        }
        val attached = bridge.attach(port) { selfCheck(port) }
        if (!attached) {
            newTunnel.stop()
            lastError = "代理自检未通过，已回退为仅应用内 DoH"
            return
        }
        tunnel = newTunnel
    }

    /**
     * 隧道看门狗：每秒确认监听线程还活着。连续 3 次异常即触发 [ProxyBridge] 熔断
     * （约 3s），对应验收里“杀掉隧道后自动 clearProxyOverride，页面继续可访问”。
     */
    private fun startWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                val active = tunnel ?: continue
                if (!active.isRunning()) {
                    bridge.noteFailure("隧道监听线程已退出")
                } else {
                    bridge.noteSuccess()
                }
            }
        }
    }

    private fun selfCheck(port: Int): Boolean = runCatching {
        val probeHost = config.probeHost
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 1_200)
            socket.soTimeout = config.timeoutMs.toInt().coerceAtLeast(1_000)
            socket.getOutputStream().apply {
                write(
                    "CONNECT $probeHost:443 HTTP/1.1\r\nHost: $probeHost:443\r\n\r\n"
                        .toByteArray(Charsets.ISO_8859_1)
                )
                flush()
            }
            val statusLine = socket.getInputStream().bufferedReader().readLine() ?: return@use false
            statusLine.contains(" 200")
        }
    }.getOrDefault(false)

    private fun onNetworkChanged(reason: String) {
        if (!running) return
        onLog?.invoke("网络变化：$reason，清理缓存并重新竞速")
        clearCache()
        pool?.reraceAsync()
        if (config.useProxy) {
            // 切网后旧隧道的出口 IP 已失效，重建（自检是阻塞 IO，放后台）
            bridge.detach()
            tunnel?.stop()
            tunnel = null
            scope.launch {
                startTunnel()
                publish()
            }
        }
        publish()
    }

    private fun publish() {
        _status.value = status()
    }

    companion object {
        private const val WATCHDOG_INTERVAL_MS = 1_000L
    }
}
