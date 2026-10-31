package cn.olfact.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DohUpstreamsTest {

    @Test
    fun `补齐协议与默认路径`() {
        assertEquals("https://dns.nextdns.io/abc123", DohUpstreams.normalize("dns.nextdns.io/abc123"))
        assertEquals("https://dns.adguard.com/dns-query", DohUpstreams.normalize("https://dns.adguard.com"))
        assertEquals("https://dns.adguard.com/dns-query", DohUpstreams.normalize("https://dns.adguard.com/"))
    }

    @Test
    fun `大小写与末尾斜杠归一`() {
        assertEquals(
            "https://doh.example.com/dns-query",
            DohUpstreams.normalize("  HTTPS://DoH.Example.com:443  ")
        )
    }

    @Test
    fun `允许 IP 与本地地址`() {
        assertTrue(DohUpstreams.parse("https://1.1.1.1/dns-query") is DohUpstreams.Result.Ok)
        val local = DohUpstreams.parse("http://127.0.0.1:8080/dns-query")
        assertTrue(local is DohUpstreams.Result.Ok)
        assertTrue((local as DohUpstreams.Result.Ok).local)
    }

    @Test
    fun `拒绝不合法的输入`() {
        assertNull(DohUpstreams.normalize(""))
        assertNull(DohUpstreams.normalize("   "))
        assertNull(DohUpstreams.normalize("ftp://dns.example.com/dns-query"))
        assertNull(DohUpstreams.normalize("https://dns example.com/dns-query"))
        assertNull(DohUpstreams.normalize("https://nodots/dns-query"))
        assertNull(DohUpstreams.normalize("http://dns.example.com/dns-query"))  // 公网必须 https
    }

    @Test
    fun `错误信息是人话`() {
        val result = DohUpstreams.parse("ftp://dns.example.com/dns-query")
        assertTrue(result is DohUpstreams.Result.Failed)
        assertEquals("只支持 https（本地可用 http）", (result as DohUpstreams.Result.Failed).reason)
    }
}
