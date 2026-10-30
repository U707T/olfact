package cn.olfact.web.doh

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class RoutingDnsTest {

    private class FakeDns(private val address: InetAddress) : Dns {
        var calls = 0
            private set
        var lastHost: String? = null
            private set

        override fun lookup(hostname: String): List<InetAddress> {
            calls++
            lastHost = hostname
            return listOf(address)
        }
    }

    private fun ip(last: Int): InetAddress = InetAddress.getByAddress(byteArrayOf(10, 0, 0, last.toByte()))

    @Test
    fun `国内域名走系统，国外走 DoH`() {
        val doh = FakeDns(ip(1))
        val system = FakeDns(ip(2))
        val routing = RoutingDns(
            rulesProvider = { DnsRules.builtin(DohMode.SMART) },
            doh = doh,
            system = system
        )

        routing.lookup("www.baidu.com")
        assertEquals(1, system.calls)
        assertEquals(0, doh.calls)

        routing.lookup("www.google.com")
        assertEquals(1, doh.calls)
        assertEquals("www.google.com", doh.lastHost)
    }

    @Test
    fun `系统通道结果按 sys 前缀缓存`() {
        val doh = FakeDns(ip(1))
        val system = FakeDns(ip(2))
        val cache = DnsCache()
        val routing = RoutingDns(
            rulesProvider = { DnsRules.builtin(DohMode.SMART) },
            doh = doh,
            system = system,
            cache = cache
        )

        routing.lookup("www.qq.com")
        routing.lookup("www.qq.com")
        assertEquals(1, system.calls)
        assertNotNull(cache.get(DnsCache.PREFIX_SYSTEM, "www.qq.com", DnsCache.TYPE_A))
        assertNull(cache.get(DnsCache.PREFIX_DOH, "www.qq.com", DnsCache.TYPE_A))
    }

    @Test
    fun `粘性会话覆盖分流决定`() {
        val doh = FakeDns(ip(1))
        val system = FakeDns(ip(2))
        var sticky = false
        val routing = RoutingDns(
            rulesProvider = { DnsRules.builtin(DohMode.SMART) },
            doh = doh,
            system = system,
            sticky = { sticky }
        )
        sticky = true
        routing.lookup("www.baidu.com")
        assertEquals(1, doh.calls)
        assertEquals(0, system.calls)
    }

    @Test
    fun `规则变化立即生效`() {
        val doh = FakeDns(ip(1))
        val system = FakeDns(ip(2))
        var rules = DnsRules.of(DohMode.ONLY_LISTED)
        val routing = RoutingDns(rulesProvider = { rules }, doh = doh, system = system)

        routing.lookup("example.com")
        assertEquals(1, system.calls)

        rules = rules.plus("example.com")
        routing.lookup("example.com")
        assertEquals(1, doh.calls)
    }
}
