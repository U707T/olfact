package cn.olfact.web.doh

/**
 * DoH 运行统计：平均解析耗时 / 缓存命中率 / 失败数 / 今日查询数。
 * 全部方法线程安全，开销是几次原子操作。
 */
class DohStats(private val clock: () -> Long = { System.currentTimeMillis() }) {

    data class Snapshot(
        val avgLatencyMs: Long,
        val cacheHitRate: Double,
        val failures: Long,
        val todayQueries: Long,
        val totalQueries: Long
    )

    private val dayMillis = 86_400_000L

    private var dayIndex: Long = clock() / dayMillis
    private var todayQueries: Long = 0L
    private var totalQueries: Long = 0L
    private var failures: Long = 0L
    private var hits: Long = 0L
    private var misses: Long = 0L
    private var latencyEmaMs: Long = -1L

    @Synchronized
    fun onQuery(latencyMs: Long, cacheHit: Boolean, failed: Boolean) {
        rollDayIfNeeded()
        todayQueries++
        totalQueries++
        if (cacheHit) {
            hits++
        } else {
            misses++
            if (!failed && latencyMs > 0) {
                latencyEmaMs = if (latencyEmaMs < 0) latencyMs else (latencyEmaMs * 3 + latencyMs) / 4
            }
        }
        if (failed) failures++
    }

    @Synchronized
    fun onFailure() {
        rollDayIfNeeded()
        failures++
    }

    @Synchronized
    fun snapshot(): Snapshot {
        rollDayIfNeeded()
        val cacheTotal = hits + misses
        val hitRate = if (cacheTotal == 0L) 0.0 else hits.toDouble() / cacheTotal
        return Snapshot(
            avgLatencyMs = latencyEmaMs.coerceAtLeast(0L),
            cacheHitRate = hitRate,
            failures = failures,
            todayQueries = todayQueries,
            totalQueries = totalQueries
        )
    }

    @Synchronized
    fun reset() {
        todayQueries = 0
        totalQueries = 0
        failures = 0
        hits = 0
        misses = 0
        latencyEmaMs = -1
        dayIndex = clock() / dayMillis
    }

    private fun rollDayIfNeeded() {
        val index = clock() / dayMillis
        if (index != dayIndex) {
            dayIndex = index
            todayQueries = 0
        }
    }
}
