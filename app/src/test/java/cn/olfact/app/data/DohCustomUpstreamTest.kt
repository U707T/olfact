package cn.olfact.app.data

import cn.olfact.web.doh.DohUpstream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 自定义 DoH 上游的解析规则（设置页「自定义上游」）。 */
class DohCustomUpstreamTest {

    @Test
    fun `内置地址被识别为预设`() {
        assertTrue(DohAdapter.isPresetUrl("https://doh.pub/dns-query"))
        assertTrue(DohAdapter.isPresetUrl("  https://dns.google/dns-query  "))
        assertFalse(DohAdapter.isPresetUrl("https://dns.example.com/dns-query"))
    }

    @Test
    fun `自定义上游同时进国内与国外池`() {
        val list = DohAdapter.buildCustomUpstreams("https://dns.example.com/dns-query")
        assertEquals(2, list.size)
        assertEquals(
            setOf(DohUpstream.Region.DOMESTIC, DohUpstream.Region.FOREIGN),
            list.map { it.region }.toSet()
        )
        assertTrue(list.all { it.url == "https://dns.example.com/dns-query" })
        assertTrue(list.all { it.label.contains("dns.example.com") })
        assertEquals(2, list.map { it.id }.toSet().size)
    }

    @Test
    fun `IP 形式的自定义上游可用`() {
        val list = DohAdapter.buildCustomUpstreams("https://94.140.14.14/dns-query")
        assertEquals(2, list.size)
        assertEquals("94.140.14.14", list.first().host)
    }

    @Test
    fun `不合法的地址退化为空表`() {
        assertTrue(DohAdapter.buildCustomUpstreams("").isEmpty())
        assertTrue(DohAdapter.buildCustomUpstreams("http://dns.example.com/dns-query").isEmpty())
        assertTrue(DohAdapter.buildCustomUpstreams("https://").isEmpty())
        assertTrue(DohAdapter.buildCustomUpstreams("auto").isEmpty())
    }

    @Test
    fun `只给主机时补通用 DNS 路径`() {
        val list = DohAdapter.buildCustomUpstreams("https://dns.example.com")
        assertEquals(2, list.size)
        assertTrue(list.all { it.url == "https://dns.example.com/dns-query" })
    }

    @Test
    fun `本地 HTTP 和 IPv6 不再静默退回内置池`() {
        listOf("http://127.0.0.1:8080/dns-query", "http://[::1]:8080/dns-query", "https://[2001:db8::1]:8443/Dns?key=A").forEach { url ->
            val list = DohAdapter.buildCustomUpstreams(url)
            assertEquals(url, 2, list.size)
            assertTrue(list.all { it.url == DohUpstreams.normalize(url) })
            assertEquals((DohUpstreams.parse(url) as DohUpstreams.Result.Ok).host, list.first().host)
        }
    }

    @Test
    fun `端口会被裁掉但不影响识别`() {
        val list = DohAdapter.buildCustomUpstreams("https://dns.example.com:8443/dns-query")
        assertEquals("dns.example.com", list.first().host)
    }
}
