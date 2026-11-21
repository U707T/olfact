package cn.olfact.core.net

import okhttp3.Dns
import java.net.InetAddress

/** Optional application-wide resolver for non-WebView clients. DoH transport must not use this DNS. */
object AppDns : Dns {
    @Volatile
    var resolver: ((String) -> List<InetAddress>)? = null

    override fun lookup(hostname: String): List<InetAddress> =
        resolver?.invoke(hostname) ?: Dns.SYSTEM.lookup(hostname)
}
