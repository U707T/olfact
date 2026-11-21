package cn.olfact.download.util

import cn.olfact.download.DownloadSpec
import cn.olfact.core.network.withCancellableResponse
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {

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
        applyHeaders(builder, spec, rangeHeader)
        return builder.build()
    }

    /**
     * 探测用的 HEAD 请求。
     *
     * 之前 MediaProbe 声称「先 HEAD」，实际调用的是 [request]（GET）——等于为了看响应头
     * 先发一次完整的 GET，大文件上既慢又可能被服务端当成真实下载。
     */
    fun headRequest(spec: DownloadSpec, url: String = spec.url): Request {
        val builder = Request.Builder().url(url).head()
        applyHeaders(builder, spec, null)
        return builder.build()
    }

    private fun applyHeaders(builder: Request.Builder, spec: DownloadSpec, rangeHeader: String?) {
        val ua = spec.userAgent
            ?: spec.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
        if (!ua.isNullOrBlank()) builder.header("User-Agent", ua)
        (spec.referer ?: spec.headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value)
            ?.takeIf { it.isNotBlank() }?.let { builder.header("Referer", it) }
        spec.headers.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank() &&
                !key.equals("User-Agent", true) && !key.equals("Referer", true) &&
                !key.equals("Range", true)
            ) {
                builder.header(key, value)
            }
        }
        if (rangeHeader != null) builder.header("Range", rangeHeader)
    }

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
}
