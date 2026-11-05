package cn.olfact.download

import cn.olfact.download.util.Http
import cn.olfact.download.util.Http.await
import okhttp3.OkHttpClient

data class ProbeResult(
    val contentLength: Long = -1L,
    val rangeSupported: Boolean = false,
    val contentType: String? = null,
    val finalUrl: String,
)

/**
 * 探测大小与是否支持 Range。先 HEAD，失败再发 `Range: bytes=0-0`。
 * 探测失败不抛异常：拿不到长度就退化成单线程流式下载。
 */
object MediaProbe {

    suspend fun probe(client: OkHttpClient, spec: DownloadSpec): ProbeResult {
        // 真的用 HEAD：只拿响应头，不触发一次完整 GET。
        val head = runCatching {
            client.newCall(Http.headRequest(spec)).execute().use { response ->
                if (response.code !in 200..299) error("HEAD ${response.code}")
                ProbeResult(
                    contentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L,
                    rangeSupported = response.header("Accept-Ranges")?.contains("bytes", true) == true,
                    contentType = response.header("Content-Type")?.substringBefore(';')?.trim(),
                    finalUrl = response.request.url.toString(),
                )
            }
        }.getOrNull()

        // HEAD 被拒 / 没有长度时，用 1 字节 Range 试探。
        if (head != null && head.contentLength > 0 && head.rangeSupported) return head

        val ranged = runCatching {
            val request = Http.request(spec, rangeHeader = "bytes=0-0")
            client.newCall(request).execute().use { response ->
                val contentRange = response.header("Content-Range")
                val total = contentRange?.substringAfter('/', "")?.toLongOrNull() ?: -1L
                ProbeResult(
                    contentLength = total,
                    rangeSupported = response.code == 206 && total > 0,
                    contentType = response.header("Content-Type")?.substringBefore(';')?.trim()
                        ?: head?.contentType,
                    finalUrl = response.request.url.toString(),
                )
            }
        }.getOrNull()

        return ranged ?: head ?: ProbeResult(finalUrl = spec.url)
    }
}
