package cn.olfact.web.doh

import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * 把 bootstrap IP 表塞给 OkHttp，避免“用系统 DNS 解析 DoH 服务器域名”的循环依赖。
 */
class BootstrapDns(private val table: Map<String, List<String>> = DohUpstream.bootstrapTable()) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val ips = table[hostname.trim().lowercase(Locale.ROOT)]
            ?: return Dns.SYSTEM.lookup(hostname)
        val resolved = ips.mapNotNull { ip ->
            runCatching { InetAddress.getByAddress(hostname, InetAddress.getByName(ip).address) }.getOrNull()
        }
        return if (resolved.isEmpty()) Dns.SYSTEM.lookup(hostname) else resolved
    }
}

/**
 * RFC 8484 的最小客户端：POST application/dns-message。
 *
 * 默认整条 call 超时 2.5s（TECH-PLAN §4.5-5），失败即抛 IOException，由上层决定竞速/兜底。
 */
class DohTransport(
    baseClient: OkHttpClient = OkHttpClient(),
    timeoutMs: Long = 2_500L,
    bootstrap: Map<String, List<String>> = DohUpstream.bootstrapTable()
) {

    val timeoutMs: Long = timeoutMs

    private val client: OkHttpClient = baseClient.newBuilder()
        .dns(BootstrapDns(bootstrap))
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .connectTimeout(timeoutMs.coerceAtMost(2_000L), TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val mediaType = "application/dns-message".toMediaType()

    @Throws(IOException::class)
    fun query(
        upstream: DohUpstream,
        qname: String,
        type: Int,
        ecs: EcsSubnet? = null
    ): DnsResponse {
        val id = Random.nextInt(1, 0xFFFF)
        val payload = DnsMessage.buildQuery(id, qname, type, ecs)
        val request = Request.Builder()
            .url(upstream.url)
            .header("Accept", "application/dns-message")
            .post(payload.toRequestBody(mediaType))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("DoH ${upstream.id} HTTP ${response.code}")
            }
            val body = response.body.bytes()
            val parsed = try {
                DnsMessage.parseResponse(body)
            } catch (e: DnsFormatException) {
                throw IOException("DoH ${upstream.id} 报文异常: ${e.message}", e)
            }
            if (parsed.id != id) throw IOException("DoH ${upstream.id} 事务 ID 不匹配")
            return parsed
        }
    }

    /** 竞速探测用的固定查询。 */
    fun probe(upstream: DohUpstream, timeoutOverrideMs: Long? = null): Long {
        val start = System.nanoTime()
        val response = query(upstream, PROBE_NAME, DnsMessage.TYPE_A, null)
        if (!response.isSuccess) throw IOException("probe rcode=${response.rcode}")
        if (response.addresses().isEmpty()) throw IOException("probe empty answer")
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        if (timeoutOverrideMs != null && elapsedMs > timeoutOverrideMs) {
            throw IOException("probe 超时 ${elapsedMs}ms")
        }
        return elapsedMs
    }

    companion object {
        const val PROBE_NAME = "www.example.com"
    }
}
