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
    fun `IPv6 地址保持括号与端口`() {
        val result = DohUpstreams.parse("https://[2001:db8::1]:8443/dns-query") as DohUpstreams.Result.Ok
        assertEquals("2001:db8::1", result.host)
        assertEquals("https://[2001:db8::1]:8443/dns-query", result.url)
        assertTrue(!result.local)
    }

    @Test
    fun `内网 IPv4 与 IPv6 HTTP 可用，公网不能降级`() {
        listOf("localhost", "box.local", "127.0.0.1", "10.2.3.4", "172.16.1.2", "172.31.1.2", "192.168.1.2", "169.254.2.3", "[::1]", "[0:0:0:0:0:0:0:1]", "[fc00::1]", "[fd12::1]", "[fe80::1]").forEach { host ->
            val result = DohUpstreams.parse("http://$host:8080/dns-query")
            assertTrue("Expected local HTTP: $host ($result)", result is DohUpstreams.Result.Ok && result.local)
        }
        listOf("1.1.1.1", "192.0.2.1", "172.15.1.1", "172.32.1.1", "localhost.evil.example", "127.0.0.1.evil.example", "10.2.3.4.evil.example", "192.168.evil.1.1", "[2001:4860:4860::8888]").forEach { host ->
            assertNull("Expected public HTTP rejection: $host", DohUpstreams.normalize("http://$host/dns-query"))
        }
    }

    @Test
    fun `错误端口凭证和片段必须拒绝`() {
        listOf(":0", ":65536", ":-1", ":abc", ":").forEach { suffix ->
            assertNull("Expected invalid port rejection: $suffix", DohUpstreams.normalize("https://dns.example.com$suffix/dns-query"))
        }
        assertNull(DohUpstreams.normalize("https://user:pass@dns.example.com/dns-query"))
        assertNull(DohUpstreams.normalize("https://dns.example.com/dns-query#fragment"))
    }

    @Test
    fun `路径和查询中的大小写与编码保持原样`() {
        assertEquals("https://dns.example.com/AbC?token=X%2By&name=Hi", DohUpstreams.normalize("HTTPS://DNS.EXAMPLE.COM:443/AbC?token=X%2By&name=Hi"))
        assertEquals("http://localhost/dns-query?name=A", DohUpstreams.normalize("http://localhost:80/?name=A"))
    }

    @Test
    fun `错误信息是人话`() {
        val result = DohUpstreams.parse("ftp://dns.example.com/dns-query")
        assertTrue(result is DohUpstreams.Result.Failed)
        assertEquals("只支持 https（本地可用 http）", (result as DohUpstreams.Result.Failed).reason)
    }
}
