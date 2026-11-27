package cn.olfact.download.util

import cn.olfact.download.DownloadSpec
import cn.olfact.core.network.withCancellableResponse
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

object Http {

    private val sensitiveHeaders = setOf("cookie", "cookie2", "authorization", "proxy-authorization")
    private const val MAX_REDIRECTS = 20

    fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
        .dns(cn.olfact.core.net.AppDns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    fun request(spec: DownloadSpec, url: String = spec.url, rangeHeader: String? = null): Request {
        val builder = Request.Builder().url(url).get()
        applyHeaders(builder, spec, url, rangeHeader)
        return builder.build()
    }

    /** HEAD probe; credentials are only attached to the spec URL's origin. */
    fun headRequest(spec: DownloadSpec, url: String = spec.url): Request {
        val builder = Request.Builder().url(url).head()
        applyHeaders(builder, spec, url, null)
        return builder.build()
    }

    private fun applyHeaders(builder: Request.Builder, spec: DownloadSpec, requestUrl: String, rangeHeader: String?) {
        val ua = spec.userAgent
            ?: spec.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
        if (!ua.isNullOrBlank()) builder.header("User-Agent", ua)
        (spec.referer ?: spec.headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value)
            ?.takeIf { it.isNotBlank() }?.let { builder.header("Referer", it) }
        val maySendSensitive = sameOrigin(spec.url, requestUrl)
        spec.headers.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank() &&
                !key.equals("User-Agent", true) && !key.equals("Referer", true) &&
                !key.equals("Range", true) && (!isSensitive(key) || maySendSensitive)
            ) {
                builder.header(key, value)
            }
        }
        if (rangeHeader != null) builder.header("Range", rangeHeader)
    }

    /**
     * Follow redirects explicitly. OkHttp's built-in redirect handling can retain custom headers;
     * after any origin transition, auth/cookie headers are removed and cannot be reintroduced.
     */
    suspend fun <T> execute(client: OkHttpClient, initial: Request, consume: (Response) -> T): T {
        val noRedirects = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        var current = initial
        repeat(MAX_REDIRECTS + 1) { hop ->
            val outcome = noRedirects.newCall(current).await { response ->
                val location = response.header("Location")
                val target = if (response.code in REDIRECT_CODES && location != null) {
                    response.request.url.resolve(location)
                } else null
                if (target != null) {
                    if (hop >= MAX_REDIRECTS) throw IOException("重定向次数过多")
                    val next = response.request.newBuilder().url(target)
                    if (!sameOrigin(response.request.url.toString(), target.toString())) {
                        sensitiveHeaders.forEach(next::removeHeader)
                    }
                    response.close()
                    ExecuteOutcome.Redirect(next.build())
                } else {
                    ExecuteOutcome.Completed(consume(response))
                }
            }
            when (outcome) {
                is ExecuteOutcome.Completed -> return outcome.value
                is ExecuteOutcome.Redirect -> current = outcome.request
            }
        }
        throw IOException("重定向次数过多")
    }

    private sealed interface ExecuteOutcome<out T> {
        data class Completed<T>(val value: T) : ExecuteOutcome<T>
        data class Redirect(val request: Request) : ExecuteOutcome<Nothing>
    }

    private fun isSensitive(name: String): Boolean = name.lowercase() in sensitiveHeaders

    private fun sameOrigin(first: String, second: String): Boolean = origin(first) != null && origin(first) == origin(second)

    private fun origin(url: String): Triple<String, String, Int>? = runCatching {
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val port = when {
            uri.port >= 0 -> uri.port
            scheme == "http" -> 80
            scheme == "https" -> 443
            else -> -1
        }
        Triple(scheme, host, port)
    }.getOrNull()

    fun requireRange(response: Response, start: Long, endInclusive: Long, total: Long? = null) {
        val match = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""")
            .matchEntire(response.header("Content-Range").orEmpty())
        if (response.code != 206 || match == null ||
            match.groupValues[1].toLongOrNull() != start ||
            match.groupValues[2].toLongOrNull() != endInclusive ||
            (total != null && match.groupValues[3].toLongOrNull() != total)
        ) throw IOException("服务器返回了不匹配的字节范围")
    }

    /** Cancellation closes the call and waits for the body consumer before cleanup or resume. */
    suspend fun <T> Call.await(consume: (Response) -> T): T = withCancellableResponse { consume(it) }

    private val REDIRECT_CODES = setOf(300, 301, 302, 303, 307, 308)
}
