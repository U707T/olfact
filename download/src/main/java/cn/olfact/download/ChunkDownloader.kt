package cn.olfact.download

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
            val file = downloadSingleStream(spec, target, progress)
            return DownloadResult(file, file.length(), DownloadEngine.CHUNKED, "服务器不支持分块，已单线程下载")
        }

        val ranges = ChunkPlanner.plan(total, chunkSize)
        // part 文件名必须过滤：fileName 可能来自网页标题 / Content-Disposition，
        // 带 `../` 时 File(workDir, ...) 会写到 workDir 外面。
        val parts = ranges.map { range -> File(workDir, partName(spec.fileName, range.index)) }
        workDir.mkdirs()

        val planFile = File(workDir, "chunk-plan.txt")
        val plan = spec.url + "\n" + total + "\n" + ranges.joinToString { it.header() }
        if (planFile.exists() && planFile.readText() != plan) {
            val prefix = partName(spec.fileName, 0).removeSuffix("0")
            workDir.listFiles().orEmpty().filter { it.name.startsWith(prefix) && it.name.removePrefix(prefix).toIntOrNull() != null }
                .forEach { if (!it.delete()) throw IOException("无法清理旧分块") }
        }
        planFile.writeText(plan)
        parts.forEachIndexed { index, part -> if (part.length() > ranges[index].length) part.delete() }
        val downloaded = AtomicLong(parts.sumOf { if (it.exists()) it.length() else 0L })
        val semaphore = Semaphore(threadCount.coerceIn(1, MAX_THREADS))
        progress?.onProgress(downloaded.get(), total)

        coroutineScope {
            ranges.map { range ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        downloadRange(spec, range, total, parts[range.index]) { delta ->
                            progress?.onProgress(downloaded.addAndGet(delta), total)
                        }
                    }
                }
            }.awaitAll()
        }

        val context = coroutineContext
        val staged = File(workDir, "merged.tmp")
        try {
            ChunkMerger.merge(parts, staged, checkActive = { context.ensureActive() })
            val actual = staged.length()
            if (actual != total) throw IOException("分块合并后大小不一致：期望 $total，实际 $actual")
            context.ensureActive()
            val merged = cn.olfact.download.util.Files.publish(staged, target) { context.ensureActive() }
            parts.forEach { it.delete() }
            return DownloadResult(merged, actual, DownloadEngine.CHUNKED)
        } finally {
            staged.delete()
        }
    }

    private suspend fun downloadRange(
        spec: DownloadSpec,
        range: ByteRange,
        total: Long,
        part: File,
        onDelta: (Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        part.parentFile?.mkdirs()
        val context = coroutineContext
        var attempt = 0
        while (true) {
            coroutineContext.ensureActive()
            val already = if (part.exists()) part.length() else 0L
            if (already >= range.length) return@withContext
            attempt++
            try {
                val from = range.start + already
                val request = Http.request(spec, rangeHeader = "bytes=$from-${range.endInclusive}")
                client.newCall(request).await { response ->
                    Http.requireRange(response, from, range.endInclusive, total)
                    val body = response.body ?: throw IOException("分块 ${range.index} 响应为空")
                    var written = 0L
                    FileOutputStream(part, true).use { sink ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(BUFFER)
                            while (true) {
                                context.ensureActive()
                                val read = input.read(buffer)
                                if (read <= 0) break
                                sink.write(buffer, 0, read)
                                written += read
                                onDelta(read.toLong())
                            }
                        }
                    }
                    val actual = part.length()
                    if (actual > range.length) {
                        part.delete()
                        onDelta(-actual)
                        throw IOException("分块 ${range.index} 超出范围 ${range.length}")
                    }
                    if (actual < range.length) throw IOException("分块 ${range.index} 数据不完整")
                }
                return@withContext
            } catch (e: CancellationException) {
                throw e
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
    ): File = withContext(Dispatchers.IO) {
        workDir.mkdirs()
        val temp = File(workDir, "single.tmp")
        val context = coroutineContext
        var attempt = 0
        while (true) {
            coroutineContext.ensureActive()
            attempt++
            try {
                client.newCall(Http.request(spec)).await { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val total = response.header("Content-Length")?.toLongOrNull() ?: -1L
                    val body = response.body ?: throw IOException("响应为空")
                    var written = 0L
                    FileOutputStream(temp, false).use { sink ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(BUFFER)
                            while (true) {
                                context.ensureActive()
                                val read = input.read(buffer)
                                if (read <= 0) break
                                sink.write(buffer, 0, read)
                                written += read
                                progress?.onProgress(written, total)
                            }
                        }
                    }
                    if (total >= 0L && written != total) throw IOException("下载数据不完整")
                }
                context.ensureActive()
                return@withContext cn.olfact.download.util.Files.publish(temp, target) { context.ensureActive() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt >= MAX_ATTEMPTS) throw e
                delay(RETRY_BASE_DELAY_MS * attempt)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        target
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
