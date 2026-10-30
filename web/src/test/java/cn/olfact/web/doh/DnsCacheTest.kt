package cn.olfact.web.doh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class DnsCacheTest {

    private fun ip(vararg bytes: Int): InetAddress =
        InetAddress.getByAddress(bytes.map { it.toByte() }.toByteArray())

    @Test
    fun `命中后按 TTL 过期`() {
        var now = 0L
        val cache = DnsCache(clock = { now })
        val address = ip(1, 2, 3, 4)
        cache.put(DnsCache.PREFIX_DOH, "a.com", DnsCache.TYPE_A, listOf(address), 300)

        assertEquals(listOf(address), cache.get(DnsCache.PREFIX_DOH, "a.com", DnsCache.TYPE_A))
        now = 299_000
        assertNotNull(cache.get(DnsCache.PREFIX_DOH, "a.com", DnsCache.TYPE_A))
        now = 300_001
        assertNull(cache.get(DnsCache.PREFIX_DOH, "a.com", DnsCache.TYPE_A))
    }

    @Test
    fun `TTL 下限夹到 60 秒`() {
        var now = 0L
        val cache = DnsCache(clock = { now })
        cache.put(DnsCache.PREFIX_DOH, "short.com", DnsCache.TYPE_A, listOf(ip(1, 1, 1, 1)), 1)
        now = 59_000
        assertNotNull(cache.get(DnsCache.PREFIX_DOH, "short.com", DnsCache.TYPE_A))
        now = 61_000
        assertNull(cache.get(DnsCache.PREFIX_DOH, "short.com", DnsCache.TYPE_A))
    }

    @Test
    fun `TTL 上限夹到 1 小时`() {
        var now = 0L
        val cache = DnsCache(clock = { now })
        cache.put(DnsCache.PREFIX_DOH, "long.com", DnsCache.TYPE_A, listOf(ip(2, 2, 2, 2)), 86_400)
        now = 3_599_000
        assertNotNull(cache.get(DnsCache.PREFIX_DOH, "long.com", DnsCache.TYPE_A))
        now = 3_601_000
        assertNull(cache.get(DnsCache.PREFIX_DOH, "long.com", DnsCache.TYPE_A))
    }

    @Test
    fun `负缓存 30 秒`() {
        var now = 0L
        val cache = DnsCache(clock = { now })
        cache.putNegative(DnsCache.PREFIX_DOH, "nx.com", DnsCache.TYPE_A)
        assertTrue(cache.lookup(DnsCache.PREFIX_DOH, "nx.com", DnsCache.TYPE_A) is DnsCache.Lookup.Negative)
        now = 31_000
        assertTrue(cache.lookup(DnsCache.PREFIX_DOH, "nx.com", DnsCache.TYPE_A) is DnsCache.Lookup.Miss)
    }

    @Test
    fun `doh 与 sys 前缀互不可见`() {
        val cache = DnsCache()
        cache.put(DnsCache.PREFIX_DOH, "a.com", DnsCache.TYPE_A, listOf(ip(3, 3, 3, 3)), 300)
        assertNull(cache.get(DnsCache.PREFIX_SYSTEM, "a.com", DnsCache.TYPE_A))
        assertNotNull(cache.get(DnsCache.PREFIX_DOH, "a.com", DnsCache.TYPE_A))
        cache.clear(DnsCache.PREFIX_DOH)
        assertNull(cache.get(DnsCache.PREFIX_DOH, "a.com", DnsCache.TYPE_A))
    }

    @Test
    fun `A 与 AAAA 分开缓存`() {
        val cache = DnsCache()
        cache.put(DnsCache.PREFIX_DOH, "a.com", DnsCache.TYPE_A, listOf(ip(4, 4, 4, 4)), 300)
        assertNull(cache.get(DnsCache.PREFIX_DOH, "a.com", DnsCache.TYPE_AAAA))
    }

    @Test
    fun `命中率统计`() {
        var now = 0L
        val cache = DnsCache(clock = { now })
        cache.put(DnsCache.PREFIX_DOH, "hit.com", DnsCache.TYPE_A, listOf(ip(5, 5, 5, 5)), 300)
        cache.get(DnsCache.PREFIX_DOH, "hit.com", DnsCache.TYPE_A)
        cache.get(DnsCache.PREFIX_DOH, "miss.com", DnsCache.TYPE_A)
        assertEquals(0.5, cache.hitRate(), 0.001)
        now = 400_000
        cache.get(DnsCache.PREFIX_DOH, "hit.com", DnsCache.TYPE_A)
        assertEquals(1.0 / 3.0, cache.hitRate(), 0.001)
    }
}
