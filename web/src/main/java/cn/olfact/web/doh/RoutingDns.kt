package cn.olfact.web.doh

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * 分流入口（TECH-PLAN §4.4）：按规则决定这次查询走 DoH 还是系统 DNS。
 *
 * 两条通道共用一张 [DnsCache]，但 key 前缀分别是 `doh:` / `sys:`，
 * 切换策略或切网时按前缀清理，绝不串味。
 */
class RoutingDns(
    private val rulesProvider: () -> DnsRules,
    private val doh: Dns,
    private val system: Dns = Dns.SYSTEM,
    private val cache: DnsCache? = null,
    private val sticky: (String) -> Boolean = { false },
    private val systemTtlSeconds: Int = 60
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val rules = rulesProvider()
        val useDoh = rules.useDoh(hostname, sticky(hostname))
        if (useDoh) return doh.lookup(hostname)
        return lookupSystem(hostname)
    }

    /** 供 UI 显示“本站是否加密”。 */
    fun isDohHost(host: String): Boolean = rulesProvider().useDoh(host, sticky(host))

    private fun lookupSystem(hostname: String): List<InetAddress> {
        if (cache == null) return system.lookup(hostname)
        when (val cached = cache.lookup(DnsCache.PREFIX_SYSTEM, hostname, DnsCache.TYPE_A)) {
            is DnsCache.Lookup.Hit -> return cached.addresses
            DnsCache.Lookup.Negative -> throw UnknownHostException("$hostname (negative cache)")
            DnsCache.Lookup.Miss -> Unit
        }
        val result = system.lookup(hostname)
        cache.put(DnsCache.PREFIX_SYSTEM, hostname, DnsCache.TYPE_A, result, systemTtlSeconds)
        return result
    }
}
