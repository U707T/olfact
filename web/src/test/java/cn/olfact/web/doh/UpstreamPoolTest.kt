package cn.olfact.web.doh

import org.junit.Assert.*
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
