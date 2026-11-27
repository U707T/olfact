package cn.olfact.download.m3u8

import cn.olfact.download.DownloadEngine
import cn.olfact.download.DownloadResult
import cn.olfact.download.DownloadSpec
import cn.olfact.download.util.Http
import cn.olfact.download.util.Http.await
import kotlinx.coroutines.CancellationException
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
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/** Playlist -> ad filter -> resumable segments -> decrypt -> merge. */
class M3u8Downloader(
    private val client: OkHttpClient = Http.defaultClient(),
    private val workDir: File,
    private val maxConcurrent: Int = 4,
    private val merger: SegmentMerger? = null,
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
        val context = coroutineContext
        val first = loadPlaylist(spec, spec.url)
        val media = if (first.isMaster) {
            val variant = first.variantFor(MAX_HEIGHT) ?: first.bestVariant()
                ?: throw IOException("master playlist 里没有可用清晰度")
            if (variant.audioGroupId in first.externalAudioGroupIds) {
                throw IOException("暂不支持需要外置音轨的 HLS master；为避免只保存视频已拒绝此流")
            }
            loadPlaylist(spec, variant.uri)
        } else first
        if (media.segments.isEmpty()) throw IOException("m3u8 里没有分片")

        val filterResult = AdBreakFilter.filter(media.segments, adConfig)
        val segments = filterResult.segments
        segments.forEach { segment ->
            listOfNotNull(segment.key, segment.mapKey).forEach { key ->
                if (key.isEncrypted && (!key.isAes128 || key.uri == null ||
                        (key.keyFormat != null && key.keyFormat != "identity"))) {
                    throw IOException("不支持的 HLS 加密方式：${key.method}")
                }
            }
        }
        val maps = segments.filter { it.mapUri != null }
            .map { Triple(it.mapUri, it.mapByteRangeLength, it.mapByteRangeOffset) }.distinct()
        if (maps.size > 1) throw IOException("暂不支持中途切换初始化分片的 HLS")

        val segmentDir = File(workDir, "segments").also { it.mkdirs() }
        val bytes = AtomicLong(0)
        val done = AtomicLong(0)
        val total = segments.size
        val semaphore = Semaphore(maxConcurrent.coerceIn(1, 8))
        val keyCache = ConcurrentHashMap<String, ByteArray>()
        suspend fun keyFor(key: M3u8Key?): ByteArray? {
            if (key == null || !key.isEncrypted) return null
            val uri = key.uri ?: throw IOException("AES-128 密钥地址为空")
            return keyCache[uri] ?: fetchBytesWithRetry(spec, uri).also {
                if (it.size != 16) throw IOException("AES-128 密钥必须是 16 字节")
                keyCache[uri] = it
            }
        }

        val initSegment = segments.firstOrNull { it.mapUri != null }
        val initFile = initSegment?.let { segment ->
            val file = File(segmentDir, "init-${mapCacheKey(segment)}.mp4")
            if (!file.exists() || file.length() == 0L) {
                val mapKey = segment.mapKey
                val key = keyFor(mapKey)
                if (key != null && mapKey?.ivHex == null) {
                    throw IOException("加密初始化分片缺少 IV")
                }
                fetchToFile(spec, segment.mapUri!!, file, key,
                    key?.let { Aes128Decryptor.parseIv(mapKey?.ivHex, segment.sequence) },
                    segment.mapByteRangeOffset, segment.mapByteRangeLength)
            }
            file
        }
        progress?.onProgress(0, total, 0)
        val files = coroutineScope {
            segments.map { segment ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        context.ensureActive()
                        // Content identity, not filtered list index: changed playlists/filters must not reuse old segments.
                        val file = File(segmentDir, "${cacheKey(segment)}.segment")
                        if (!file.exists() || file.length() == 0L) {
                            val key = keyFor(segment.key)
                            fetchToFile(spec, segment.uri, file, key,
                                key?.let { Aes128Decryptor.parseIv(segment.key?.ivHex, segment.sequence) },
                                segment.byteRangeOffset, segment.byteRangeLength)
                        }
                        bytes.addAndGet(file.length())
                        progress?.onProgress(done.incrementAndGet().toInt(), total, bytes.get())
                        file
                    }
                }
            }.awaitAll()
        }

        context.ensureActive()
        val outputBase = File(target.parentFile, target.nameWithoutExtension)
        target.parentFile?.mkdirs()
        val outcome = (merger ?: M3u8Merger(checkActive = { context.ensureActive() }))
            .merge(files, outputBase, initFile)
        if (outcome.file.length() == 0L) throw IOException("合并后的文件为空")
        DownloadResult(outcome.file, outcome.file.length(), DownloadEngine.M3U8, buildString {
            if (filterResult.dropped > 0) append("已过滤 ${filterResult.dropped} 个广告分片")
            if (filterResult.fallbackToAll) append("（广告过滤命中全部，已回退完整列表）")
            outcome.message?.let { if (isNotEmpty()) append("；"); append(it) }
        }.ifEmpty { null })
    }

    private suspend fun loadPlaylist(spec: DownloadSpec, url: String): M3u8Playlist =
        Http.execute(client, Http.request(spec, url)) { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}：$url")
            val body = response.body ?: throw IOException("响应为空：$url")
            M3u8Parser.parse(body.string(), response.request.url.toString())
        }

    private suspend fun fetchBytesWithRetry(spec: DownloadSpec, url: String): ByteArray {
        var attempt = 0
        while (true) {
            coroutineContext.ensureActive()
            attempt++
            try {
                return Http.execute(client, Http.request(spec, url)) { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}：$url")
                    (response.body ?: throw IOException("响应为空：$url")).bytes()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt >= MAX_ATTEMPTS) throw e
                delay(RETRY_BASE_DELAY_MS * attempt)
            }
        }
    }

    /** Only fully received/decrypted segments become resumable cache entries. */
    private suspend fun fetchToFile(
        spec: DownloadSpec,
        url: String,
        target: File,
        key: ByteArray?,
        iv: ByteArray?,
        offset: Long?,
        length: Long?,
    ): Long {
        val context = coroutineContext
        val staged = File(target.parentFile, target.name + ".part")
        var attempt = 0
        while (true) {
            context.ensureActive()
            attempt++
            try {
                val end = if (length != null && offset != null) offset + length - 1 else null
                val range = end?.let { "bytes=$offset-$it" }
                Http.execute(client, Http.request(spec, url, range)) { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}：$url")
                    if (end != null) Http.requireRange(response, offset!!, end)
                    val body = response.body ?: throw IOException("响应为空：$url")
                    val raw = body.byteStream()
                    val input = if (key != null && iv != null) {
                        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
                        cipher.init(javax.crypto.Cipher.DECRYPT_MODE,
                            javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.IvParameterSpec(iv))
                        javax.crypto.CipherInputStream(raw, cipher)
                    } else raw
                    input.use { ins ->
                        staged.outputStream().use { outs ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                context.ensureActive()
                                val count = ins.read(buffer)
                                if (count < 0) break
                                outs.write(buffer, 0, count)
                            }
                        }
                    }
                    if (staged.length() == 0L || (key == null && length != null && staged.length() != length)) {
                        throw IOException("分片数据不完整：$url")
                    }
                }
                context.ensureActive()
                if (!staged.renameTo(target)) throw IOException("无法保存分片")
                return target.length()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt >= MAX_ATTEMPTS) throw e
                delay(RETRY_BASE_DELAY_MS * attempt)
            } finally {
                staged.delete()
            }
        }
    }

    private fun mapCacheKey(segment: M3u8Segment): String = MessageDigest.getInstance("SHA-256")
        .digest(listOf(segment.mapUri, segment.mapByteRangeOffset, segment.mapByteRangeLength, segment.mapKey)
            .joinToString("|").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun cacheKey(segment: M3u8Segment): String = MessageDigest.getInstance("SHA-256")
        .digest(segment.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_BASE_DELAY_MS = 700L
        private const val MAX_HEIGHT = 1080
    }
}
