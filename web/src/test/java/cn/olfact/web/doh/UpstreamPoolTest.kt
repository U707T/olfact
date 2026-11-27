package cn.olfact.web.doh

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Test

class UpstreamPoolTest {
    @Test fun `preferred built-in outside the configured pool is never selected`() {
        val custom = DohUpstream("custom", "custom", "https://own.example/dns", DohUpstream.Region.FOREIGN, false, emptyList())
        val pool = UpstreamPool(DohTransport(), listOf(custom), preferredId = "cloudflare")
        assertEquals(custom, pool.pick("foreign.example"))
        assertEquals(custom, pool.pick("baidu.com"))
    }

    @Test fun `custom preferred upstream is selected before racing`() {
        val custom = DohUpstream("custom", "custom", "https://own.example/dns", DohUpstream.Region.FOREIGN, false, emptyList())
        val pool = UpstreamPool(DohTransport(), listOf(DohUpstream.CLOUDFLARE, custom), preferredId = custom.id)
        assertEquals(custom, pool.pick("foreign.example"))
    }

    @Test fun `cutover abandons a running race and selects the new network winner`() {
        val firstRaceStarted = CountDownLatch(2)
        val releaseFirstRace = CountDownLatch(1)
        val revision = AtomicInteger()
        val requests = AtomicInteger()
        val completions = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val network = revision.get()
            requests.incrementAndGet()
            if (network == 0) {
                firstRaceStarted.countDown()
                check(releaseFirstRace.await(4, TimeUnit.SECONDS))
            }
            val request = chain.request()
            val query = Buffer().also { request.body!!.writeTo(it) }.readByteArray()
            val success = request.url.host == if (network == 0) "first.example" else "second.example"
            val out = ByteArrayOutputStream()
            DataOutputStream(out).apply {
                write(query, 0, 2)
                writeShort(0x8180)
                writeShort(1)
                writeShort(1)
                writeInt(0)
                write(query, 12, query.size - 12)
                writeShort(0xc00c); writeShort(1); writeShort(1)
                writeInt(60); writeShort(4); write(byteArrayOf(192.toByte(), 0, 2, 1))
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (success) 200 else 503).message("test").body(out.toByteArray().toResponseBody()).build()
        }.build()
        val first = DohUpstream.CLOUDFLARE.copy(id = "first", url = "https://first.example/dns-query")
        val second = DohUpstream.QUAD9.copy(id = "second", url = "https://second.example/dns-query")
        val pool = UpstreamPool(DohTransport(client, timeoutMs = 5_000), listOf(first, second), onEvent = completions::add)
        val result = FutureTask { pool.raceAndSelect() }
        thread(isDaemon = true) { result.run() }
        try {
            assertTrue(firstRaceStarted.await(3, TimeUnit.SECONDS))
            revision.incrementAndGet()
            pool.onNetworkChanged()
        } finally {
            releaseFirstRace.countDown()
        }
        val selected = result.get(4, TimeUnit.SECONDS)
        assertEquals(second, selected.foreign)
        assertEquals(setOf(second.id), selected.latencies.keys)
        assertEquals(4, requests.get())
        assertEquals(1, completions.size)
    }

    @Test fun `upstream hosts parse HTTP ports and IPv6`() {
        val base = DohUpstream.ALI
        assertEquals("127.0.0.1", base.copy(url = "http://127.0.0.1:8080/dns-query").host)
        assertEquals("2001:db8::1", base.copy(url = "https://[2001:db8::1]:443/dns-query").host)
    }

    @Test fun `preset matching ignores host case and default port but preserves path case`() {
        assertEquals(DohUpstream.ALI, DohUpstream.byUrl("HTTPS://DNS.ALIDNS.COM:443/dns-query/"))
        assertNull(DohUpstream.byUrl("https://dns.alidns.com/DNS-QUERY"))
    }
}
