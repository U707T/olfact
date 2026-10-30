package cn.olfact.web.doh

import okhttp3.Dns
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 真联网验证，默认跳过：
 * `./gradlew :web:testDebugUnitTest -Polfact.liveDoh=1 --tests '*LiveDohTest*'`
 */
class LiveDohTest {

    private val live: Boolean = System.getProperty("olfact.liveDoh") == "1"

    @Test(timeout = 60_000)
    fun `五路竞速并解析域名`() {
        assumeTrue("未开启联网测试（-Polfact.liveDoh=1）", live)
        val transport = DohTransport(timeoutMs = 2_500)
        val pool = UpstreamPool(transport, DohUpstream.DEFAULTS)
        val selection = pool.raceAndSelect()
        println("selection=$selection")
        assertNotNull(selection.domestic)
        assertNotNull(selection.foreign)

        val stats = DohStats()
        val dns = DohDns(pool, transport, DnsCache(), stats, { DohConfig() })
        try {
            val addresses = dns.lookup("www.example.com")
            println("www.example.com -> $addresses")
            assertTrue(addresses.isNotEmpty())

            dns.lookup("www.example.com")
            println("stats=${stats.snapshot()}")
            assertTrue(stats.snapshot().cacheHitRate > 0.0)
        } finally {
            dns.shutdown()
        }
    }

    @Test(timeout = 60_000)
    fun `通过隧道 CONNECT 真实站点`() {
        assumeTrue("未开启联网测试（-Polfact.liveDoh=1）", live)
        val transport = DohTransport(timeoutMs = 2_500)
        val pool = UpstreamPool(transport, DohUpstream.DEFAULTS)
        pool.raceAndSelect()
        val dns = DohDns(pool, transport, DnsCache(), DohStats(), { DohConfig() })
        val routing = RoutingDns({ DnsRules.builtin(DohMode.SMART) }, dns, Dns.SYSTEM, null)
        val tunnel = TunnelProxy(resolver = { host -> routing.lookup(host) })
        val port = tunnel.start(0)
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 2_000)
                socket.soTimeout = 10_000
                socket.getOutputStream().apply {
                    write("CONNECT www.gstatic.com:443 HTTP/1.1\r\nHost: www.gstatic.com:443\r\n\r\n".toByteArray())
                    flush()
                }
                val status = BufferedReader(InputStreamReader(socket.getInputStream())).readLine()
                println("CONNECT status=$status")
                assertTrue("实际：$status", status.contains(" 200"))
                println("tunnel stats=${tunnel.stats()}")
            }
        } finally {
            tunnel.stop()
            dns.shutdown()
        }
    }
}
