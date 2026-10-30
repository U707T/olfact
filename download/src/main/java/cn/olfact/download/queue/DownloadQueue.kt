package cn.olfact.download.queue

import android.content.Context
import cn.olfact.download.ChunkDownloader
import cn.olfact.download.DownloadConfig
import cn.olfact.download.DownloadEngine
import cn.olfact.download.DownloadKind
import cn.olfact.download.DownloadPaths
import cn.olfact.download.DownloadRecord
import cn.olfact.download.DownloadSpec
import cn.olfact.download.DownloadStatus
import cn.olfact.download.SystemDownloader
import cn.olfact.download.m3u8.M3u8Downloader
import cn.olfact.download.notify.DownloadNotifier
import cn.olfact.download.util.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * 下载队列：入队 / 并发调度 / 进度持久化 / 取消重试。
 * UI（B5）只读 [items]，所有状态变化都写回 [DownloadStore]，杀进程不丢。
 */
class DownloadQueue(
    private val context: Context,
    private val store: DownloadStore,
    private val configProvider: () -> DownloadConfig,
    private val notifierProvider: () -> DownloadNotifier? = { null },
    private val client: OkHttpClient = Http.defaultClient(),
) {

    private val _items = MutableStateFlow(store.all())
    val items: StateFlow<List<DownloadRecord>> = _items.asStateFlow()

    private val jobs = ConcurrentHashMap<String, Job>()
    private val lastProgressPush = ConcurrentHashMap<String, Long>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---------- 对外操作 ----------

    fun enqueue(spec: DownloadSpec, engine: DownloadEngine? = null): DownloadRecord {
        val config = configProvider()
        if (!spec.allowDuplicate) {
            store.all().firstOrNull { it.spec.dedupKey == spec.dedupKey && it.status.isActive }?.let { return it }
        }
        val record = DownloadRecord(
            id = spec.newId(),
            spec = spec,
            engine = engine ?: chooseEngine(spec, config),
        )
        store.upsert(record)
        publish()
        return record
    }

    fun cancel(id: String) {
        val record = store.get(id) ?: return
        store.upsert(record.copy(status = DownloadStatus.CANCELED, updatedAt = now()))
        jobs.remove(id)?.cancel()
        record.systemDownloadId?.let { SystemDownloader(context).cancel(it) }
        notifierProvider()?.cancel(id)
        publish()
    }

    fun pause(id: String) {
        val record = store.get(id) ?: return
        if (record.status != DownloadStatus.RUNNING && record.status != DownloadStatus.QUEUED) return
        store.upsert(record.copy(status = DownloadStatus.PAUSED, updatedAt = now()))
        jobs.remove(id)?.cancel()
        publish()
    }

    fun resume(id: String) {
        val record = store.get(id) ?: return
        if (record.status != DownloadStatus.PAUSED && record.status != DownloadStatus.FAILED) return
        store.upsert(record.copy(status = DownloadStatus.QUEUED, error = null, updatedAt = now()))
        publish()
    }

    fun retry(id: String) = resume(id)

    fun remove(id: String) {
        val record = store.get(id)
        if (record != null) {
            jobs.remove(id)?.cancel()
            record.systemDownloadId?.let { SystemDownloader(context).cancel(it) }
            record.filePath?.let { runCatching { File(it).delete() } }
        }
        store.remove(id)
        notifierProvider()?.cancel(id)
        publish()
    }

    fun refresh() = publish()

    /** 系统下载器在进程外继续跑：重新进入应用时把状态对齐。 */
    fun reconcile() {
        val system = SystemDownloader(context)
        store.all()
            .filter { it.engine == DownloadEngine.SYSTEM && it.systemDownloadId != null }
            .filter { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.PAUSED }
            .forEach { record ->
                val id = record.systemDownloadId ?: return@forEach
                val progress = system.progressOf(id) ?: return@forEach
                when {
                    progress.isComplete -> store.upsert(
                        record.copy(
                            status = DownloadStatus.COMPLETED,
                            bytesDownloaded = if (progress.totalBytes > 0) progress.totalBytes else progress.downloadedBytes,
                            totalBytes = progress.totalBytes,
                            filePath = system.localUri(id) ?: record.filePath,
                            updatedAt = now(),
                        )
                    )

                    progress.isFailed -> store.upsert(
                        record.copy(status = DownloadStatus.FAILED, error = "系统下载失败", updatedAt = now())
                    )

                    else -> store.upsert(
                        record.copy(
                            status = DownloadStatus.RUNNING,
                            bytesDownloaded = progress.downloadedBytes,
                            totalBytes = progress.totalBytes,
                            updatedAt = now(),
                        )
                    )
                }
            }
        publish()
    }

    /**
     * 处理队列。由 [DownloadWorker] 调用；也可以在界面里直接调（前台下载）。
     * 并发上限 [DownloadConfig.maxConcurrentTasks]。
     */
    suspend fun processQueue(): Int = coroutineScope {
        val config = configProvider()
        // 上次进程被杀时处于 RUNNING 的任务：自动重新排队（part 文件还在，会续传）
        store.all().filter { it.status == DownloadStatus.RUNNING }.forEach {
            store.upsert(it.copy(status = DownloadStatus.QUEUED, error = "进程重启，自动续传", updatedAt = now()))
        }
        publish()

        val queued = store.all().filter { it.status == DownloadStatus.QUEUED }.sortedBy { it.createdAt }
        if (queued.isEmpty()) return@coroutineScope 0

        val semaphore = Semaphore(config.maxConcurrentTasks.coerceIn(1, 4))
        queued.map { record ->
            async { semaphore.withPermit { runOne(record.id, config) } }
        }.awaitAll()
        queued.size
    }

    // ---------- 内部 ----------

    private suspend fun runOne(id: String, config: DownloadConfig) {
        var record = store.get(id) ?: return
        if (record.status != DownloadStatus.QUEUED) return

        record = record.copy(status = DownloadStatus.RUNNING, error = null, updatedAt = now())
        store.upsert(record)
        publish()
        notifierProvider()?.notifyProgress(record, force = true)
        jobs[id] = kotlinx.coroutines.currentCoroutineContext()[Job] ?: return

        try {
            val finished = when (record.engine) {
                DownloadEngine.SYSTEM -> runSystem(record, config)
                DownloadEngine.CHUNKED -> runChunked(record, config)
                DownloadEngine.M3U8 -> runM3U8(record, config)
            }
            store.upsert(finished)
            publish()
            notifierProvider()?.notifyCompleted(finished)
        } catch (e: CancellationException) {
            val latest = store.get(id)
            if (latest != null && latest.status == DownloadStatus.RUNNING) {
                store.upsert(latest.copy(status = DownloadStatus.PAUSED, error = "已暂停", updatedAt = now()))
            }
            publish()
            throw e
        } catch (e: Exception) {
            val failed = record.copy(
                status = DownloadStatus.FAILED,
                error = e.message ?: e.javaClass.simpleName,
                updatedAt = now(),
            )
            store.upsert(failed)
            publish()
            notifierProvider()?.notifyFailed(failed)
        } finally {
            jobs.remove(id)
        }
    }

    private suspend fun runSystem(record: DownloadRecord, config: DownloadConfig): DownloadRecord {
        val downloader = SystemDownloader(context)
        val subPath = "olfact/" + DownloadPaths.plannedName(record.spec)
        val systemId = downloader.enqueue(record.spec, config, subPath)
        var current = record.copy(systemDownloadId = systemId, updatedAt = now())
        store.upsert(current)
        publish()

        while (true) {
            delay(POLL_INTERVAL_MS)
            if (store.get(record.id)?.status == DownloadStatus.CANCELED) {
                downloader.cancel(systemId)
                throw CancellationException("已取消")
            }
            val progress = downloader.progressOf(systemId) ?: continue
            when {
                progress.isComplete -> return current.copy(
                    status = DownloadStatus.COMPLETED,
                    bytesDownloaded = if (progress.totalBytes > 0) progress.totalBytes else progress.downloadedBytes,
                    totalBytes = progress.totalBytes,
                    filePath = downloader.localUri(systemId) ?: subPath,
                    updatedAt = now(),
                )

                progress.isFailed -> throw IOException("系统下载失败（reason=${progress.reason}）")

                else -> {
                    current = current.copy(
                        bytesDownloaded = progress.downloadedBytes,
                        totalBytes = progress.totalBytes,
                        updatedAt = now(),
                    )
                    pushProgress(current)
                }
            }
        }
    }

    private suspend fun runChunked(record: DownloadRecord, config: DownloadConfig): DownloadRecord {
        val workDir = DownloadPaths.workDir(context, record.id).also { it.mkdirs() }
        val target = DownloadPaths.resolveTarget(context, record.spec)
        val downloader = ChunkDownloader(client, workDir)
        val threads = record.spec.threadCount.takeIf { it > 0 } ?: config.threadsPerTask
        val chunkSize = record.spec.chunkSizeBytes.takeIf { it > 0 } ?: config.chunkSizeBytes

        val result = downloader.download(record.spec, target, threads, chunkSize) { done, total ->
            val latest = store.get(record.id) ?: return@download
            pushProgress(latest.copy(bytesDownloaded = done, totalBytes = total, updatedAt = now()))
        }
        return record.copy(
            status = DownloadStatus.COMPLETED,
            bytesDownloaded = result.bytes,
            totalBytes = result.bytes,
            filePath = result.file.absolutePath,
            error = result.message,
            updatedAt = now(),
        )
    }

    private suspend fun runM3U8(record: DownloadRecord, config: DownloadConfig): DownloadRecord {
        val workDir = DownloadPaths.workDir(context, record.id).also { it.mkdirs() }
        val target = DownloadPaths.resolveTarget(context, record.spec)
        val downloader = M3u8Downloader(client, workDir, config.threadsPerTask)

        val result = downloader.download(record.spec, target, config.adFilter) { done, total, bytes ->
            val latest = store.get(record.id) ?: return@download
            pushProgress(
                latest.copy(
                    segmentsDone = done,
                    segmentsTotal = total,
                    bytesDownloaded = bytes,
                    updatedAt = now(),
                )
            )
        }
        return record.copy(
            status = DownloadStatus.COMPLETED,
            bytesDownloaded = result.bytes,
            totalBytes = result.bytes,
            filePath = result.file.absolutePath,
            error = result.message,
            updatedAt = now(),
        )
    }

    /** 进度写盘节流：UI 每次都更新，磁盘最多 800ms 一次。 */
    private fun pushProgress(record: DownloadRecord) {
        val now = now()
        val last = lastProgressPush[record.id] ?: 0L
        if (now - last >= PROGRESS_PERSIST_INTERVAL_MS) {
            lastProgressPush[record.id] = now
            store.upsert(record)
        }
        _items.value = store.all().map { if (it.id == record.id) record else it }
            .sortedByDescending { it.createdAt }
        notifierProvider()?.notifyProgress(record)
    }

    private fun publish() {
        _items.value = store.all()
    }

    private fun chooseEngine(spec: DownloadSpec, config: DownloadConfig): DownloadEngine = when {
        spec.kind == DownloadKind.HLS -> DownloadEngine.M3U8
        spec.kind == DownloadKind.PROGRESSIVE && config.preferSystemDownloader -> DownloadEngine.SYSTEM
        else -> DownloadEngine.CHUNKED
    }

    private fun now() = System.currentTimeMillis()

    /** 供界面主动触发一次队列处理（不等 WorkManager）。 */
    fun kick() {
        scope.launch { runCatching { processQueue() } }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 1_000L
        private const val PROGRESS_PERSIST_INTERVAL_MS = 800L
    }
}
