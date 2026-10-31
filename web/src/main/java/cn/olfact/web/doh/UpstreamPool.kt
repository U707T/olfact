package cn.olfact.web.doh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    private var domesticBest: DohUpstream? = DohUpstream.byId(preferredId)
        ?.takeIf { it.region == DohUpstream.Region.DOMESTIC }
        ?: upstreams.firstOrNull { it.region == DohUpstream.Region.DOMESTIC }

    @Volatile
    private var foreignBest: DohUpstream? = DohUpstream.byId(preferredId)
        ?.takeIf { it.region == DohUpstream.Region.FOREIGN }
        ?: upstreams.firstOrNull { it.region == DohUpstream.Region.FOREIGN }

    private val latencies = ConcurrentHashMap<String, Long>()
    private val failures = ConcurrentHashMap<String, AtomicInteger>()

    @Volatile
    private var racing = false

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
    fun raceAndSelect(): Selection {
        racing = true
        try {
            val results = runBlocking {
                upstreams.map { upstream ->
                    async(Dispatchers.IO) { upstream to probeOrNull(upstream) }
                }.awaitAll()
            }
            val succeeded = results.filter { it.second != null }
            // 用户显式选过的上游优先：只要它探测通过，就不让竞速把它换掉；
            // 它挂了才回落到同池最快的一个。
            val pinned = upstreams.firstOrNull { it.id == preferredId }
            val pinnedLatency = pinned?.let { p -> succeeded.firstOrNull { it.first.id == p.id }?.second }

            val domestic = if (pinned != null && pinned.region == DohUpstream.Region.DOMESTIC && pinnedLatency != null) {
                pinned
            } else {
                succeeded.filter { it.first.region == DohUpstream.Region.DOMESTIC }
                    .minByOrNull { it.second ?: Long.MAX_VALUE }?.first
            }
            val foreign = if (pinned != null && pinned.region == DohUpstream.Region.FOREIGN && pinnedLatency != null) {
                pinned
            } else {
                succeeded.filter { it.first.region == DohUpstream.Region.FOREIGN }
                    .minByOrNull { it.second ?: Long.MAX_VALUE }?.first
            }

            if (domestic != null) domesticBest = domestic
            if (foreign != null) foreignBest = foreign
            if (domestic == null && foreign != null) domesticBest = foreign
            if (foreign == null && domestic != null) foreignBest = domestic

            failures.clear()
            onEvent(
                "竞速完成：国内=${domesticBest?.label ?: "-"}(${domesticBest?.let { latencyOf(it.id) } ?: -1}ms) " +
                    "国外=${foreignBest?.label ?: "-"}(${foreignBest?.let { latencyOf(it.id) } ?: -1}ms)"
            )
            return selection()
        } finally {
            racing = false
        }
    }

    fun reraceAsync() {
        if (racing) return
        scope.launch { runCatching { raceAndSelect() } }
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

    private fun probeOrNull(upstream: DohUpstream): Long? = runCatching {
        val latency = transport.probe(upstream)
        latencies[upstream.id] = latency
        latency
    }.getOrNull()

    companion object {
        /** 单次竞速最多等这么久，超过即视为该上游失败。 */
        const val RACE_TIMEOUT_MS = 3_000L
    }
}
