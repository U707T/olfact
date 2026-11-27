package cn.olfact.web.doh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 上游池：启动/切网时 5 路并发竞速，国内池与国外池各记一个最快；
 * 某个上游连续失败 3 次触发重新竞速（TECH-PLAN §4.5-3）。
 */
class UpstreamPool(
    private val transport: DohTransport,
    private val upstreams: List<DohUpstream> = DohUpstream.DEFAULTS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val onEvent: (String) -> Unit = {},
    private val preferredId: String? = null,
    private val failureThreshold: Int = 3
) {

    data class Selection(
        val domestic: DohUpstream?,
        val foreign: DohUpstream?,
        val latencies: Map<String, Long>
    )

    @Volatile
    private var domesticBest: DohUpstream? = upstreams.firstOrNull { it.id == preferredId }
        ?.takeIf { it.region == DohUpstream.Region.DOMESTIC }
        ?: upstreams.firstOrNull { it.region == DohUpstream.Region.DOMESTIC }

    @Volatile
    private var foreignBest: DohUpstream? = upstreams.firstOrNull { it.id == preferredId }
        ?.takeIf { it.region == DohUpstream.Region.FOREIGN }
        ?: upstreams.firstOrNull { it.region == DohUpstream.Region.FOREIGN }

    private val latencies = ConcurrentHashMap<String, Long>()
    private val failures = ConcurrentHashMap<String, AtomicInteger>()

    private val raceLock = Any()
    private var racing = false
    private var networkRevision = 0L

    fun currentDomestic(): DohUpstream? = domesticBest

    fun currentForeign(): DohUpstream? = foreignBest

    /** 按 host 归属挑池子：国内域名优先国内池，国外域名优先国外池，缺一个就回退另一个。 */
    fun pick(host: String): DohUpstream? =
        if (DomesticSites.isDomestic(host)) {
            domesticBest ?: foreignBest
        } else {
            foreignBest ?: domesticBest
        }

    fun latencyOf(id: String): Long = latencies[id] ?: -1L

    fun selection(): Selection = Selection(domesticBest, foreignBest, latencies.toMap())

    /**
     * 阻塞式竞速，5 路并发，最慢 2.5s 出结果。返回每个池子的冠军。
     */
    fun raceAndSelect(): Selection = runBlocking { race() }

    private suspend fun race(): Selection {
        var revision = synchronized(raceLock) {
            if (racing) return selection()
            racing = true
            networkRevision
        }
        try {
            while (true) {
                val results = coroutineScope {
                    upstreams.map { upstream ->
                        async(Dispatchers.IO) { upstream to runCatching { transport.probe(upstream) }.getOrNull() }
                    }.awaitAll()
                }
                val selected = synchronized(raceLock) {
                    if (revision != networkRevision) {
                        revision = networkRevision
                        null
                    } else {
                        select(results).also { racing = false }
                    }
                } ?: continue
                return selected.also {
                    runCatching {
                        onEvent(
                            "竞速完成：国内=${it.domestic?.label ?: "-"}(${it.domestic?.let { u -> it.latencies[u.id] } ?: -1}ms) " +
                                "国外=${it.foreign?.label ?: "-"}(${it.foreign?.let { u -> it.latencies[u.id] } ?: -1}ms)"
                        )
                    }
                }
            }
        } catch (error: Throwable) {
            synchronized(raceLock) { racing = false }
            throw error
        }
    }

    private fun select(results: List<Pair<DohUpstream, Long?>>): Selection {
        val succeeded = results.filter { it.second != null }
        succeeded.forEach { (upstream, latency) -> latencies[upstream.id] = checkNotNull(latency) }
        fun winner(region: DohUpstream.Region): DohUpstream? {
            val candidates = succeeded.filter { it.first.region == region }
            return candidates.firstOrNull { it.first.id == preferredId }?.first
                ?: candidates.minByOrNull { it.second ?: Long.MAX_VALUE }?.first
        }
        val domestic = winner(DohUpstream.Region.DOMESTIC)
        val foreign = winner(DohUpstream.Region.FOREIGN)
        domesticBest = domestic ?: foreign ?: domesticBest
        foreignBest = foreign ?: domestic ?: foreignBest
        failures.clear()
        return selection()
    }

    fun reraceAsync() {
        scope.launch { race() }
    }

    /** 旧网络的一场竞速不得提交；正在竞速时由同一任务补跑最新网络。 */
    fun onNetworkChanged() {
        synchronized(raceLock) {
            networkRevision++
            if (racing) return
        }
        reraceAsync()
    }

    fun recordSuccess(upstream: DohUpstream, latencyMs: Long) {
        if (latencyMs > 0) {
            val previous = latencies[upstream.id]
            latencies[upstream.id] = if (previous == null) latencyMs else (previous * 3 + latencyMs) / 4
        }
        failures[upstream.id]?.set(0)
    }

    fun recordFailure(upstream: DohUpstream) {
        val counter = failures.computeIfAbsent(upstream.id) { AtomicInteger() }
        val count = counter.incrementAndGet()
        if (count >= failureThreshold) {
            counter.set(0)
            onEvent("${upstream.label} 连续失败 $count 次，重新竞速")
            reraceAsync()
        }
    }

    fun consecutiveFailures(upstream: DohUpstream): Int = failures[upstream.id]?.get() ?: 0

}
