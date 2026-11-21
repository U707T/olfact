package cn.olfact.web.doh

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Application interceptor supplies DNS wire responses; no network or system DNS is used. */
class DohDnsTest {
    private val resolvers = ArrayList<DohDns>()
    private val requests = AtomicInteger()
    private val fallbackCalls = AtomicInteger()
    private val upstream = DohUpstream("test", "test", "https://resolver.test/dns-query", DohUpstream.Region.FOREIGN, false, emptyList())
    private val ipv4 = byteArrayOf(192.toByte(), 0, 2, 8)
    private val ipv6 = ByteArray(16).apply { this[0] = 0x20; this[1] = 1; this[15] = 8 }

    @After fun close() = resolvers.forEach { it.shutdown() }

    private fun transport(answer: (ByteArray, Int) -> ByteArray): DohTransport {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val query = Buffer().also { chain.request().body!!.writeTo(it) }.readByteArray()
            val type = ((query[query.size - 4].toInt() and 255) shl 8) or (query[query.size - 3].toInt() and 255)
            requests.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(answer(query, type).toResponseBody()).build()
        }.build()
        return DohTransport(client, 5_000)
    }

    private fun dns(
        transport: DohTransport,
        config: DohConfig = DohConfig(),
        cache: DnsCache = DnsCache()
    ): DohDns = DohDns(UpstreamPool(transport, listOf(upstream)), transport, cache, DohStats(), { config },
        object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                fallbackCalls.incrementAndGet()
                return listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 9)))
            }
        }).also { resolvers += it }

    private fun response(query: ByteArray, type: Int, data: ByteArray?, cname: Boolean = false, flags: Int = 0x8180): ByteArray {
        val output = ByteArrayOutputStream()
        val writer = DataOutputStream(output)
        writer.write(query.copyOfRange(0, 2))
        writer.writeShort(flags)
        writer.writeShort(1)
        writer.writeShort((if (data != null) 1 else 0) + (if (cname) 1 else 0))
        writer.writeInt(0)
        writer.write(query.copyOfRange(12, query.size))
        if (cname) {
            writer.writeShort(0xc00c); writer.writeShort(5); writer.writeShort(1)
            writer.writeInt(60); writer.writeShort(2); writer.writeShort(0xc00c)
        }
        if (data != null) {
            writer.writeShort(0xc00c); writer.writeShort(type); writer.writeShort(1)
            writer.writeInt(300); writer.writeShort(data.size); writer.write(data)
        }
        return output.toByteArray()
    }

    @Test fun `CNAME before addresses preserves both families on cold and warm lookup`() {
        val dns = dns(transport { query, type -> response(query, type, if (type == 1) ipv4 else ipv6, cname = true) })
        val expected = setOf(InetAddress.getByAddress(ipv4), InetAddress.getByAddress(ipv6))
        assertEquals(expected, dns.lookup("cdn.example").toSet())
        assertEquals(expected, dns.lookup("cdn.example").toSet())
        assertEquals(2, requests.get())
        assertEquals(0, fallbackCalls.get())
    }

    @Test fun `IPv6 only answer is cached together with A NODATA`() {
        val dns = dns(transport { query, type -> response(query, type, if (type == 28) ipv6 else null) })
        assertEquals(listOf(InetAddress.getByAddress(ipv6)), dns.lookup("ipv6.example"))
        assertEquals(listOf(InetAddress.getByAddress(ipv6)), dns.lookup("ipv6.example"))
        assertEquals(2, requests.get())
    }

    @Test fun `CNAME TTL limits the address cache lifetime`() {
        var now = 0L
        val cache = DnsCache(clock = { now }, minTtlMs = 0)
        val dns = dns(transport { query, type -> response(query, type, ipv4, cname = true) }, DohConfig(enableIpv6 = false), cache)
        dns.lookup("cdn.example")
        now = 60_001
        dns.lookup("cdn.example")
        assertEquals(2, requests.get())
    }

    @Test fun `failed AAAA does not discard successful A`() {
        val dns = dns(transport { query, type ->
            if (type == 28) throw IOException("v6 unavailable")
            response(query, type, ipv4)
        })
        assertEquals(listOf(InetAddress.getByAddress(ipv4)), dns.lookup("ipv4.example"))
        assertEquals(0, fallbackCalls.get())
    }

    @Test fun `NODATA fallback remains usable on later lookups`() {
        val dns = dns(transport { query, type -> response(query, type, null) })
        dns.lookup("split.example")
        dns.lookup("split.example")
        assertEquals(2, fallbackCalls.get())
    }

    @Test fun `NXDOMAIN is negatively cached without system fallback`() {
        val dns = dns(transport { query, type -> response(query, type, null, flags = 0x8183) })
        repeat(2) { assertThrows(UnknownHostException::class.java) { dns.lookup("missing.example") } }
        assertEquals(2, requests.get())
        assertEquals(0, fallbackCalls.get())
    }

    @Test fun `old clients fall back after shutdown`() {
        val dns = dns(transport { _, _ -> fail("closed resolver must not query"); byteArrayOf() })
        dns.shutdown()
        assertEquals("127.0.0.9", dns.lookup("after.example").single().hostAddress)
    }

    @Test(timeout = 5_000) fun `both address families share one deadline and unfinished tasks are interrupted`() {
        val finished = CountDownLatch(2)
        val dns = dns(transport { _, _ ->
            try { CountDownLatch(1).await(); byteArrayOf() } finally { finished.countDown() }
        }, DohConfig(timeoutMs = 300))
        val start = System.nanoTime()
        dns.lookup("slow.example")
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertTrue("lookup took ${elapsed}ms", elapsed < 1_900)
        assertTrue("query tasks not cancelled", finished.await(500, TimeUnit.MILLISECONDS))
        assertEquals(1, fallbackCalls.get())
    }

    @Test fun `cache clear during lookup discards the late DoH result`() {
        val cache = DnsCache()
        val dns = dns(transport { query, type ->
            cache.clear()
            response(query, type, ipv4)
        }, DohConfig(enableIpv6 = false), cache)
        dns.lookup("late.example")
        assertEquals(0, cache.size())
        dns.lookup("late.example")
        assertEquals(2, requests.get())
    }

    @Test fun `transport rejects truncated messages even when they contain addresses`() {
        val transport = transport { query, type -> response(query, type, ipv4, flags = 0x8380) }
        assertThrows(IOException::class.java) { transport.query(upstream, "a.example", 1) }
    }

    @Test fun `transport bounds DNS response size`() {
        val transport = transport { _, _ -> ByteArray(65_536) }
        assertThrows(IOException::class.java) { transport.query(upstream, "a.example", 1) }
    }
}
