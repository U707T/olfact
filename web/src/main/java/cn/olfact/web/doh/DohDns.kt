package cn.olfact.web.doh

import okhttp3.Dns
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * L1：A / AAAA 并发解析，按记录类型缓存；失败时退回系统 DNS。
 * 两个查询共用一个等待期限，未完成的任务在退出时取消。
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
    @Volatile private var closed = false

    override fun lookup(hostname: String): List<InetAddress> {
        val host = hostname.trim().trimEnd('.').lowercase(Locale.ROOT)
        if (host.isEmpty()) throw UnknownHostException(hostname)
        if (EcsSubnet.isIpLiteral(host)) return InetAddress.getAllByName(host).toList()
        // 已交给下载器的旧 client 可能比 manager 活得久，停用后回落系统解析。
        if (closed) return fallback.lookup(host)

        val cfg = configProvider()
        val cacheGeneration = cache.generation()
        val types = if (cfg.enableIpv6) listOf(DnsMessage.TYPE_A, DnsMessage.TYPE_AAAA) else listOf(DnsMessage.TYPE_A)
        val cachedAddresses = ArrayList<InetAddress>()
        val missing = types.filter { type ->
            when (val cached = cache.lookup(DnsCache.PREFIX_DOH, host, type)) {
                is DnsCache.Lookup.Hit -> { cachedAddresses += cached.addresses; false }
                DnsCache.Lookup.Negative -> false
                DnsCache.Lookup.Miss -> true
            }
        }
        if (missing.isEmpty()) {
            stats.onQuery(0, cacheHit = true, failed = false)
            return cachedAddresses.distinct().ifEmpty { throw UnknownHostException("$host (negative cache)") }
        }

        val upstream = pool.pick(host)
        if (upstream == null) {
            stats.onQuery(0, cacheHit = false, failed = true)
            return cachedAddresses.distinct().ifEmpty { fallback.lookup(host) }
        }
        val ecs = if (upstream.supportsEcs) cfg.ecs() else null
        val started = System.nanoTime()
        val deadline = started + TimeUnit.MILLISECONDS.toNanos(cfg.timeoutMs + 800)
        val completion = ExecutorCompletionService<Pair<Int, DnsResponse>>(executor)
        val tasks = ArrayList<Future<Pair<Int, DnsResponse>>>(missing.size)
        val answers = LinkedHashMap<Int, DnsResponse>()
        try {
            missing.forEach { type ->
                tasks += completion.submit(Callable { type to transport.query(upstream, host, type, ecs) })
            }
            repeat(tasks.size) {
                val remaining = deadline - System.nanoTime()
                val completed = if (remaining > 0) completion.poll(remaining, TimeUnit.NANOSECONDS) else null
                if (completed != null) {
                    try {
                        val (type, response) = completed.get()
                        if (!response.truncated) answers[type] = response
                    } catch (_: java.util.concurrent.ExecutionException) {
                        // 单个地址族失败不丢弃另一个地址族的结果。
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            // shutdown 与 lookup 并发时不把执行器异常传给 OkHttp。
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw UnknownHostException("$host (interrupted)").apply { initCause(e) }
        } finally {
            tasks.forEach { if (!it.isDone) it.cancel(true) }
        }

        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        val addresses = ArrayList(cachedAddresses)
        answers.forEach { (type, response) ->
            if (response.isSuccess) {
                val typed = response.records.filter { it.type == type }
                val resolved = response.copy(records = typed).addresses()
                addresses += resolved
                if (!closed && (resolved.isNotEmpty() || cachedAddresses.isNotEmpty() ||
                    answers.values.any { it.isSuccess && it.addresses().isNotEmpty() })
                ) {
                    cache.put(DnsCache.PREFIX_DOH, host, type, resolved, response.minTtlSeconds, cacheGeneration)
                }
            } else if (response.isNameError && !closed) {
                cache.putNegative(DnsCache.PREFIX_DOH, host, type, cacheGeneration)
            }
        }
        if (addresses.isNotEmpty()) {
            pool.recordSuccess(upstream, elapsed)
            stats.onQuery(elapsed, cacheHit = false, failed = false)
            return addresses.distinct().sortedBy { it.address.size }
        }
        if (answers.values.any { it.isNameError }) {
            stats.onQuery(elapsed, cacheHit = false, failed = false)
            throw UnknownHostException(host)
        }
        pool.recordFailure(upstream)
        stats.onQuery(elapsed, cacheHit = false, failed = true)
        return fallback.lookup(host)
    }

    fun shutdown() {
        closed = true
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
