package cn.olfact.web.doh

import java.net.InetAddress
import java.util.Locale

/**
 * TTL 感知的 DNS 缓存，带负缓存与 key 前缀隔离。
 *
 * 前缀约定（TECH-PLAN §4.5-2）：`doh:` 与 `sys:` 分开，切换策略时按前缀清，
 * 避免同一个域名换通道后拿到另一条通道的旧结果。
 */
class DnsCache(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val minTtlMs: Long = 60_000L,
    private val maxTtlMs: Long = 3_600_000L,
    private val negativeTtlMs: Long = 30_000L,
    private val maxEntries: Int = 1024
) {

    sealed class Lookup {
        data class Hit(val addresses: List<InetAddress>) : Lookup()
        data object Negative : Lookup()
        data object Miss : Lookup()
    }

    private class Entry(val addresses: List<InetAddress>?, val expireAt: Long)

    private val lock = Any()
    private val map = LinkedHashMap<String, Entry>(128, 0.75f, true)

    private var hits = 0L
    private var misses = 0L

    fun lookup(prefix: String, host: String, type: Int): Lookup {
        val key = keyOf(prefix, host, type)
        synchronized(lock) {
            val entry = map[key]
            if (entry == null) {
                misses++
                return Lookup.Miss
            }
            if (entry.expireAt <= clock()) {
                map.remove(key)
                misses++
                return Lookup.Miss
            }
            hits++
            val addresses = entry.addresses
            return if (addresses == null) Lookup.Negative else Lookup.Hit(addresses)
        }
    }

    /** 命中返回地址，未命中/负缓存/过期返回 null。 */
    fun get(prefix: String, host: String, type: Int): List<InetAddress>? =
        when (val result = lookup(prefix, host, type)) {
            is Lookup.Hit -> result.addresses
            else -> null
        }

    fun put(prefix: String, host: String, type: Int, addresses: List<InetAddress>, ttlSeconds: Int) {
        if (addresses.isEmpty()) {
            putNegative(prefix, host, type)
            return
        }
        val ttlMs = (ttlSeconds.coerceAtLeast(0) * 1000L).coerceIn(minTtlMs, maxTtlMs)
        putEntry(prefix, host, type, Entry(addresses.toList(), clock() + ttlMs))
    }

    fun putNegative(prefix: String, host: String, type: Int) {
        putEntry(prefix, host, type, Entry(null, clock() + negativeTtlMs.coerceAtLeast(0)))
    }

    private fun putEntry(prefix: String, host: String, type: Int, entry: Entry) {
        val key = keyOf(prefix, host, type)
        synchronized(lock) {
            map[key] = entry
            if (map.size > maxEntries) {
                val iterator = map.keys.iterator()
                while (map.size > maxEntries && iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }
    }

    /** @param prefix 传 null 清全部；推荐分别清 `doh:` / `sys:` */
    fun clear(prefix: String? = null) {
        synchronized(lock) {
            if (prefix == null) {
                map.clear()
            } else {
                val p = normalizePrefix(prefix)
                map.keys.removeAll { it.startsWith(p) }
            }
        }
    }

    fun size(): Int = synchronized(lock) { map.size }

    fun resetStats() = synchronized(lock) {
        hits = 0
        misses = 0
    }

    /** 命中率，无查询时返回 0。 */
    fun hitRate(): Double = synchronized(lock) {
        val total = hits + misses
        if (total == 0L) 0.0 else hits.toDouble() / total
    }

    fun hitsCount(): Long = synchronized(lock) { hits }

    fun missesCount(): Long = synchronized(lock) { misses }

    private fun keyOf(prefix: String, host: String, type: Int): String =
        "${normalizePrefix(prefix)}$type:${host.trim().lowercase(Locale.ROOT)}"

    private fun normalizePrefix(prefix: String): String {
        val p = prefix.trim()
        return when {
            p.isEmpty() -> ""
            p.endsWith(':') -> p
            else -> "$p:"
        }
    }

    companion object {
        const val PREFIX_DOH = "doh:"
        const val PREFIX_SYSTEM = "sys:"

        const val TYPE_A = DnsMessage.TYPE_A
        const val TYPE_AAAA = DnsMessage.TYPE_AAAA
    }
}
