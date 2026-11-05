package cn.olfact.download

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
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

data class DownloadResult(
    val file: File,
    val bytes: Long,
    val engine: DownloadEngine,
    val message: String? = null,
)

/**
 * 多线程分块下载（OkHttp Range）。
 * 每个分块一个 part 文件，进程被杀后重启能从已有 part 续传；
 * 全部完成后按序拼成目标文件。
 */
class ChunkDownloader(
    private val client: OkHttpClient = Http.defaultClient(),
    private val workDir: File,
) {

    fun interface ProgressListener {
        fun onProgress(downloadedBytes: Long, totalBytes: Long)
    }

    suspend fun download(
        spec: DownloadSpec,
        target: File,
        threadCount: Int = DEFAULT_THREADS,
        chunkSize: Long = ChunkPlanner.DEFAULT_CHUNK_SIZE,
        progress: ProgressListener? = null,
    ): DownloadResult {
        val probe = MediaProbe.probe(client, spec)
        val total = probe.contentLength

        if (total <= 0L || !probe.rangeSupported) {
            val bytes = downloadSingleStream(spec, target, progress)
            return DownloadResult(target, bytes, DownloadEngine.CHUNKED, "服务器不支持分块，已单线程下载")
        }

        val ranges = ChunkPlanner.plan(total, chunkSize)
        // part 文件名必须过滤：fileName 可能来自网页标题 / Content-Disposition，
        // 带 `../` 时 File(workDir, ...) 会写到 workDir 外面。
        val parts = ranges.map { range -> File(workDir, partName(spec.fileName, range.index)) }
        workDir.mkdirs()

        val downloaded = AtomicLong(parts.sumOf { if (it.exists()) it.length() else 0L })
        val semaphore = Semaphore(threadCount.coerceIn(1, MAX_THREADS))
        progress?.onProgress(downloaded.get(), total)

        coroutineScope {
            ranges.map { range ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        downloadRange(spec, range, parts[range.index]) { delta ->
                            progress?.onProgress(downloaded.addAndGet(delta), total)
                        }
                    }
                }
            }.awaitAll()
        }

        val merged = ChunkMerger.merge(parts, target, deleteParts = true)
        val expected = total
        val actual = merged.length()
        if (actual != expected) {
            // 大小不对的文件不能留给用户：删掉再报错，任务会显示失败而不是「已完成但打不开」。
            runCatching { merged.delete() }
            throw IOException("分块合并后大小不一致：期望 $expected，实际 $actual")
        }
        return DownloadResult(merged, actual, DownloadEngine.CHUNKED)
    }

    private suspend fun downloadRange(
        spec: DownloadSpec,
        range: ByteRange,
        part: File,
        onDelta: (Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        part.parentFile?.mkdirs()
        var attempt = 0
        while (true) {
            coroutineContext.ensureActive()
            val already = if (part.exists()) part.length() else 0L
            if (already >= range.length) return@withContext
            attempt++
            try {
                val from = range.start + already
                val request = Http.request(spec, rangeHeader = "bytes=$from-${range.endInclusive}")
                client.newCall(request).await().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("分块 ${range.index} HTTP ${response.code}")
                    }
                    // 服务端忽略了 Range：整段重下（只在第一块且无进度时可行）
                    if (response.code == 200 && already > 0L) {
                        throw IOException("服务器返回 200，无法继续分块 ${range.index}")
                    }
                    val body = response.body ?: throw IOException("分块 ${range.index} 响应为空")
                    var written = 0L
                    FileOutputStream(part, true).use { sink ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(BUFFER)
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read <= 0) break
                                sink.write(buffer, 0, read)
                                written += read
                                onDelta(read.toLong())
                            }
                        }
                    }
                    if (written == 0L && already + written < range.length) {
                        throw IOException("分块 ${range.index} 没有收到数据")
                    }
                    // 服务器忽略 Range 时会回 200 + 整个文件：每个分块都把整份数据写进来，
                    // 合并后必然对不上。删掉这块再报错，否则重试会把它当成「已下完」直接跳过。
                    if (part.length() > range.length) {
                        runCatching { part.delete() }
                        throw IOException("分块 ${range.index} 收到 ${part.length()} 字节，超出范围 ${range.length}（服务器忽略 Range）")
                    }
                    return@withContext
                }
            } catch (e: Exception) {
                if (attempt >= MAX_ATTEMPTS) throw e
                delay(RETRY_BASE_DELAY_MS * attempt)
            }
        }
    }

    private suspend fun downloadSingleStream(
        spec: DownloadSpec,
        target: File,
        progress: ProgressListener?,
    ): Long = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        var attempt = 0
        while (true) {
            coroutineContext.ensureActive()
            attempt++
            try {
                client.newCall(Http.request(spec)).await().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val total = response.header("Content-Length")?.toLongOrNull() ?: -1L
                    val body = response.body ?: throw IOException("响应为空")
                    var written = 0L
                    FileOutputStream(temp, false).use { sink ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(BUFFER)
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read <= 0) break
                                sink.write(buffer, 0, read)
                                written += read
                                progress?.onProgress(written, total)
                            }
                        }
                    }
                    if (!temp.renameTo(target)) {
                        temp.copyTo(target, overwrite = true)
                        temp.delete()
                    }
                    return@withContext written
                }
            } catch (e: Exception) {
                if (attempt >= MAX_ATTEMPTS) throw e
                delay(RETRY_BASE_DELAY_MS * attempt)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        0L
    }

    companion object {
        const val DEFAULT_THREADS = 4
        const val MAX_THREADS = 8
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_BASE_DELAY_MS = 800L
        private const val BUFFER = 64 * 1024

        /** 分块文件名：文件名部分按落盘规则清洗，杜绝 `../` 之类的路径穿越。 */
        internal fun partName(fileName: String, index: Int): String =
            "${cn.olfact.download.util.Files.sanitizeFileName(fileName, "download")}.part$index"
    }
}
