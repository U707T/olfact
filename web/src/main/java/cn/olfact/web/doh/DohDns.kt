package cn.olfact.web.doh

import okhttp3.Dns
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * L1：给 OkHttp/下载/AI 用的 Dns 实现。
 *
 * - A / AAAA 并发查询，任一成功即可返回（v6 优先不阻塞 v4）
 * - TTL 感知缓存 + NXDOMAIN 负缓存
 * - 上游失败自动记失败次数，全失败时退回系统 DNS，保证“不白屏”
 */
class DohDns(
    private val pool: UpstreamPool,
    private val transport: DohTransport,
    private val cache: DnsCache,
    private val stats: DohStats,
    private val configProvider: () -> DohConfig,
    private val fallback: Dns = Dns.SYSTEM
) : Dns {

    private val executor: ExecutorService = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "olfact-doh").apply { isDaemon = true }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val host = hostname.trim().trimEnd('.').lowercase(Locale.ROOT)
        if (host.isEmpty()) throw UnknownHostException(hostname)

        if (EcsSubnet.isIpLiteral(host)) {
            return InetAddress.getAllByName(host).toList()
        }

        when (val cached = cache.lookup(DnsCache.PREFIX_DOH, host, DnsCache.TYPE_A)) {
            is DnsCache.Lookup.Hit -> {
                stats.onQuery(0, cacheHit = true, failed = false)
                return cached.addresses
            }
            DnsCache.Lookup.Negative -> {
                stats.onQuery(0, cacheHit = true, failed = false)
                throw UnknownHostException("$host (negative cache)")
            }
            DnsCache.Lookup.Miss -> Unit
        }

        val cfg = configProvider()
        val upstream = pool.pick(host)
        if (upstream == null) {
            stats.onQuery(0, cacheHit = false, failed = true)
            return fallback.lookup(host)
        }
        val ecs = if (upstream.supportsEcs) cfg.ecs() else null

        val started = System.currentTimeMillis()
        val answers = LinkedHashMap<Int, DnsResponse>()
        val tasks = ArrayList<Future<DnsResponse>>(2)
        tasks += executor.submit(Callable { transport.query(upstream, host, DnsMessage.TYPE_A, ecs) })
        if (cfg.enableIpv6) {
            tasks += executor.submit(Callable { transport.query(upstream, host, DnsMessage.TYPE_AAAA, ecs) })
        }

        var failed = false
        var nameError = false
        tasks.forEach { task ->
            try {
                val response = task.get(cfg.timeoutMs + 800, TimeUnit.MILLISECONDS)
                if (response.isNameError) nameError = true
                if (response.isSuccess && response.records.isNotEmpty()) {
                    answers[response.records.first().type] = response
                }
            } catch (e: Exception) {
                failed = true
            }
        }

        val elapsed = System.currentTimeMillis() - started
        val addresses = answers.values.flatMap { it.addresses() }.distinct()

        if (addresses.isEmpty()) {
            if (nameError) {
                cache.putNegative(DnsCache.PREFIX_DOH, host, DnsCache.TYPE_A)
                stats.onQuery(elapsed, cacheHit = false, failed = false)
                throw UnknownHostException(host)
            }
            pool.recordFailure(upstream)
            stats.onQuery(elapsed, cacheHit = false, failed = true)
            return fallback.lookup(host)
        }

        pool.recordSuccess(upstream, elapsed)
        stats.onQuery(elapsed, cacheHit = false, failed = failed && addresses.isEmpty())
        answers.forEach { (type, response) ->
            cache.put(
                DnsCache.PREFIX_DOH,
                host,
                type,
                response.addresses(),
                response.minTtlSeconds
            )
        }
        return addresses
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    /** 一次性解析（不走缓存），给嗅探/探测这类低频调用用。 */
    @Throws(IOException::class)
    fun resolveUncached(host: String, type: Int): DnsResponse {
        val upstream = pool.pick(host) ?: throw IOException("没有可用 DoH 上游")
        val ecs = if (upstream.supportsEcs) configProvider().ecs() else null
        return transport.query(upstream, host, type, ecs)
    }
}
