package cn.olfact.download.m3u8

import cn.olfact.download.DownloadEngine
import cn.olfact.download.DownloadResult
import cn.olfact.download.DownloadSpec
import cn.olfact.download.util.Http
import cn.olfact.download.util.Http.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * m3u8 下载：解析 → 广告过滤 → 并发下分片 → 解密 → remux。
 * 分片缓存在 workDir 里，重试时已下载的分片不会重复拉。
 */
class M3u8Downloader(
    private val client: OkHttpClient = Http.defaultClient(),
    private val workDir: File,
    private val maxConcurrent: Int = 4,
    private val merger: SegmentMerger = M3u8Merger(),
) {

    fun interface ProgressListener {
        fun onProgress(segmentsDone: Int, segmentsTotal: Int, bytesDownloaded: Long)
    }

    suspend fun download(
        spec: DownloadSpec,
        target: File,
        adConfig: AdFilterConfig = AdFilterConfig(),
        progress: ProgressListener? = null,
    ): DownloadResult = withContext(Dispatchers.IO) {
        val first = loadPlaylist(spec, spec.url)
        val media = if (first.isMaster) {
            val variant = first.variantFor(MAX_HEIGHT) ?: first.bestVariant()
                ?: throw IOException("master playlist 里没有可用清晰度")
            loadPlaylist(spec, variant.uri)
        } else {
            first
        }
        if (media.segments.isEmpty()) throw IOException("m3u8 里没有分片（直播或加密格式不支持）")

        val filterResult = AdBreakFilter.filter(media.segments, adConfig)
        val segments = filterResult.segments

        val segmentDir = File(workDir, "segments-" + spec.fileName.hashCode().toUInt().toString(16))
        segmentDir.mkdirs()

        val bytes = AtomicLong(0)
        val done = AtomicLong(0)
        val total = segments.size
        val semaphore = Semaphore(maxConcurrent.coerceIn(1, 8))
        val keyCache = ConcurrentHashMap<String, ByteArray>()

        // 初始化分片（fMP4 才有）
        val initUri = segments.firstOrNull { it.mapUri != null }?.mapUri
        val initFile = initUri?.let { uri ->
            val file = File(segmentDir, "init.mp4")
            if (!file.exists() || file.length() == 0L) {
                file.writeBytes(fetchBytes(spec, uri))
            }
            file
        }

        progress?.onProgress(0, total, 0)

        val files = coroutineScope {
            segments.mapIndexed { index, segment ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        coroutineContext.ensureActive()
                        val file = File(segmentDir, "%05d.ts".format(index))
                        if (!file.exists() || file.length() == 0L) {
                            val key = segment.key
                            val keyBytes = if (key != null && key.isAes128 && key.uri != null) {
                                keyCache.getOrPut(key.uri) { fetchBytesWithRetry(spec, key.uri) }
                            } else {
                                null
                            }
                            val iv = if (keyBytes != null && key != null) {
                                Aes128Decryptor.parseIv(key.ivHex, segment.sequence)
                            } else {
                                null
                            }
                            // 边下边解密边写文件：不再把整个分片读进 ByteArray
                            val size = fetchToFile(spec, segment.uri, file, keyBytes, iv)
                            bytes.addAndGet(size)
                        } else {
                            bytes.addAndGet(file.length())
                        }
                        progress?.onProgress(done.incrementAndGet().toInt(), total, bytes.get())
                        file
                    }
                }
            }.awaitAll()
        }

        val outputBase = File(target.parentFile, target.nameWithoutExtension)
        target.parentFile?.mkdirs()
        val outcome = merger.merge(files, outputBase, initFile)
        if (outcome.file.length() == 0L) throw IOException("合并后的文件为空")

        DownloadResult(
            file = outcome.file,
            bytes = outcome.file.length(),
            engine = DownloadEngine.M3U8,
            message = buildString {
                if (filterResult.dropped > 0) append("已过滤 ${filterResult.dropped} 个广告分片")
                if (filterResult.fallbackToAll) append("（广告过滤命中全部，已回退完整列表）")
                outcome.message?.let { if (isNotEmpty()) append("；"); append(it) }
            }.ifEmpty { null },
        )
    }

    private suspend fun loadPlaylist(spec: DownloadSpec, url: String): M3u8Playlist {
        val text = fetchText(spec, url)
        return M3u8Parser.parse(text, url)
    }

    private suspend fun fetchText(spec: DownloadSpec, url: String): String {
        val request = Http.request(spec, url)
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}：$url")
            val body = response.body ?: throw IOException("响应为空：$url")
            return body.string()
        }
    }

    private suspend fun fetchBytesWithRetry(spec: DownloadSpec, url: String): ByteArray {
        var attempt = 0
        while (true) {
            coroutineContext.ensureActive()
            attempt++
            try {
                return fetchBytes(spec, url)
            } catch (e: Exception) {
                if (attempt >= MAX_ATTEMPTS) throw e
                delay(RETRY_BASE_DELAY_MS * attempt)
            }
        }
    }

    private suspend fun fetchBytes(spec: DownloadSpec, url: String): ByteArray {
        client.newCall(Http.request(spec, url)).await().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}：$url")
            val body = response.body ?: throw IOException("响应为空：$url")
            return body.bytes()
        }
    }

    /**
     * 下载分片并直接写文件；AES-128 分片用 [javax.crypto.CipherInputStream] 流式解密。
     * 失败会删除半成品后重试，避免下次误判为「已下载」。
     */
    private suspend fun fetchToFile(
        spec: DownloadSpec,
        url: String,
        target: File,
        key: ByteArray?,
        iv: ByteArray?
    ): Long {
        var attempt = 0
        while (true) {
            coroutineContext.ensureActive()
            attempt++
            try {
                client.newCall(Http.request(spec, url)).await().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}：$url")
                    val body = response.body ?: throw IOException("响应为空：$url")
                    target.parentFile?.mkdirs()
                    val raw = body.byteStream()
                    val input = if (key != null && iv != null) {
                        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
                        cipher.init(
                            javax.crypto.Cipher.DECRYPT_MODE,
                            javax.crypto.spec.SecretKeySpec(key, "AES"),
                            javax.crypto.spec.IvParameterSpec(iv)
                        )
                        javax.crypto.CipherInputStream(raw, cipher)
                    } else {
                        raw
                    }
                    input.use { ins ->
                        target.outputStream().use { outs -> ins.copyTo(outs, 64 * 1024) }
                    }
                    return target.length()
                }
            } catch (e: Exception) {
                runCatching { target.delete() }
                if (attempt >= MAX_ATTEMPTS) throw e
                delay(RETRY_BASE_DELAY_MS * attempt)
            }
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_BASE_DELAY_MS = 700L
        private const val MAX_HEIGHT = 1080
    }
}
