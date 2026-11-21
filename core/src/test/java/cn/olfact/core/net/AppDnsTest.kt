package cn.olfact.core.net

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

class AppDnsTest {
    @Test
    fun `a resolver change applies to existing clients and null restores system DNS`() {
        val previous = AppDns.resolver
        try {
            val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 2))
            val client = okhttp3.OkHttpClient.Builder().dns(AppDns).build()
            var queried = ""
            AppDns.resolver = { host -> queried = host; listOf(loopback) }
            assertEquals(listOf(loopback), client.dns.lookup("resolver-test.invalid"))
            assertEquals("resolver-test.invalid", queried)
            AppDns.resolver = null
            assertEquals(Dns.SYSTEM.lookup("localhost"), client.dns.lookup("localhost"))
        } finally {
            AppDns.resolver = previous
        }
    }
}
