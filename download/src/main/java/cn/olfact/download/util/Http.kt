package cn.olfact.download.util

import cn.olfact.download.DownloadSpec
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

object Http {

    fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    fun request(spec: DownloadSpec, url: String = spec.url, rangeHeader: String? = null): Request {
        val builder = Request.Builder().url(url).get()
        val ua = spec.userAgent
            ?: spec.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
        if (!ua.isNullOrBlank()) builder.header("User-Agent", ua)
        spec.referer?.takeIf { it.isNotBlank() }?.let { builder.header("Referer", it) }
        spec.headers.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank() &&
                !key.equals("User-Agent", true) && !key.equals("Referer", true) &&
                !key.equals("Range", true)
            ) {
                builder.header(key, value)
            }
        }
        if (rangeHeader != null) builder.header("Range", rangeHeader)
        return builder.build()
    }

    /** 可取消的同步请求：协程取消时真正 cancel 掉 socket，而不是等超时。 */
    suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
        cont.invokeOnCancellation { runCatching { cancel() } }
    }
}
