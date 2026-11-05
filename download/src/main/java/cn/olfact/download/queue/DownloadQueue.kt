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
import cn.olfact.download.util.SpeedMeter
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

    // 首帧不等队列文件：先给空表，读盘在后台补一次（几百条记录时这一步能省几十毫秒）
    private val _items = MutableStateFlow<List<DownloadRecord>>(emptyList())
    val items: StateFlow<List<DownloadRecord>> = _items.asStateFlow()

    private val jobs = ConcurrentHashMap<String, Job>()
    /** 同一任务只允许一个执行者（WorkManager 与前台 kick 可能同时触发）。 */
    private val activeIds = ConcurrentHashMap.newKeySet<String>()
    private val lastProgressPush = ConcurrentHashMap<String, Long>()

    /** 全局 UI 推送节流：进度回调可能每秒上百次，不能每次都重建整张列表。 */
    @Volatile
    private var lastUiPush: Long = 0L
    /** 每个任务的实时速度（字节/秒）。只存在内存里，重启后从 0 重新估算。 */
    private val speedMeter = SpeedMeter()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch { runCatching { publish() } }
    }

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
        speedMeter.reset(id)
        record.systemDownloadId?.let { SystemDownloader(context).cancel(it) }
        notifierProvider()?.cancel(id)
        publish()
    }

    fun pause(id: String) {
        val record = store.get(id) ?: return
        if (record.status != DownloadStatus.RUNNING && record.status != DownloadStatus.QUEUED) return
        store.upsert(record.copy(status = DownloadStatus.PAUSED, updatedAt = now()))
        jobs.remove(id)?.cancel()
        speedMeter.reset(id)
        // DownloadManager 没有"暂停"语义，只能取消（进度丢失），恢复时重新入队
        if (record.engine == DownloadEngine.SYSTEM) {
            record.systemDownloadId?.let { SystemDownloader(context).cancel(it) }
        }
        publish()
    }

    fun resume(id: String) {
        val record = store.get(id) ?: return
        if (record.status != DownloadStatus.PAUSED && record.status != DownloadStatus.FAILED) return
        store.upsert(record.copy(status = DownloadStatus.QUEUED, error = null, updatedAt = now()))
        publish()
    }

    fun retry(id: String) = resume(id)

    /**
     * 删除一条任务记录。
     *
     * [deleteFile] = true：连已下载的成品文件一起删（详情页「删除下载」）；
     * [deleteFile] = false：只删记录，保留成品文件（列表「删除记录 / 清空已下载」）。
     *
     * 之前的实现不管传什么都会删文件：`remove(id, false)` 被适配成 `cancel()`，
     * 记录反而留着并显示成「失败」；`clearFinished()` 又把用户下载好的文件删了。
     * 现在临时目录（part / 分片）总是清掉，成品文件只在明确要求时删。
     */
    fun remove(id: String, deleteFile: Boolean = true) {
        val record = store.get(id)
        if (record != null) {
            jobs.remove(id)?.cancel()
            record.systemDownloadId?.let { SystemDownloader(context).cancel(it) }
            if (deleteFile) deleteArtifact(record)
            runCatching { DownloadPaths.workDir(context, id).deleteRecursively() }
        }
        speedMeter.reset(id)
        lastProgressPush.remove(id)
        store.remove(id)
        notifierProvider()?.cancel(id)
        publish()
    }

    /** 删除成品文件：content:// 走 ContentResolver，普通路径还要处理「相对 Download/」的系统下载记录。 */
    private fun deleteArtifact(record: DownloadRecord) {
        val path = record.filePath ?: return
        runCatching {
            if (path.startsWith("content://")) {
                context.contentResolver.delete(android.net.Uri.parse(path), null, null)
            } else {
                DownloadPaths.resolveExisting(context, path)?.delete()
            }
        }
    }

    fun refresh() = publish()

    /** 当前任务的实时速度（字节/秒）；不在下载中时返回 0。 */
    fun speedOf(id: String): Long {
        val record = store.get(id) ?: return 0L
        return if (record.status == DownloadStatus.RUNNING) speedMeter.speedOf(id) else 0L
    }

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
        // 上次进程被杀时处于 RUNNING 的任务：
        // - 自研引擎：part 文件还在，重新排队续传；
        // - 系统下载器：DownloadManager 可能还在下，保留 id 继续跟踪，绝不重复入队。
        store.all()
            .filter { it.status == DownloadStatus.RUNNING }
            .filterNot { it.engine == DownloadEngine.SYSTEM && it.systemDownloadId != null }
            .forEach {
                store.upsert(it.copy(status = DownloadStatus.QUEUED, error = "进程重启，自动续传", updatedAt = now()))
            }
        publish()

        val queued = store.all().filter { it.status == DownloadStatus.QUEUED }.sortedBy { it.createdAt }
        val tracking = store.all()
            .filter { it.status == DownloadStatus.RUNNING && it.engine == DownloadEngine.SYSTEM && it.systemDownloadId != null }
            .map { it.id }
        val ids = (queued.map { it.id } + tracking).distinct()
        if (ids.isEmpty()) return@coroutineScope 0

        val semaphore = Semaphore(config.maxConcurrentTasks.coerceIn(1, 4))
        ids.map { id ->
            async { semaphore.withPermit { runOne(id, config) } }
        }.awaitAll()
        queued.size
    }

    // ---------- 内部 ----------

    private suspend fun runOne(id: String, config: DownloadConfig) {
        var record = store.get(id) ?: return
        val isSystemTracking = record.status == DownloadStatus.RUNNING &&
            record.engine == DownloadEngine.SYSTEM && record.systemDownloadId != null
        if (record.status != DownloadStatus.QUEUED && !isSystemTracking) return
        if (!activeIds.add(id)) return

        record = record.copy(status = DownloadStatus.RUNNING, error = null, updatedAt = now())
        store.upsert(record)
        publish()
        if (config.notifyProgress) notifierProvider()?.notifyProgress(record, force = true)
        jobs[id] = kotlinx.coroutines.currentCoroutineContext()[Job] ?: return

        try {
            val finished = when (record.engine) {
                DownloadEngine.SYSTEM -> runSystem(record, config)
                DownloadEngine.CHUNKED -> runChunked(record, config)
                DownloadEngine.M3U8 -> runM3U8(record, config)
            }
            // 用户可能在下载结束前删掉了这条记录：别把结果写回去，否则任务会「复活」。
            if (store.get(id) == null) return
            store.upsert(finished)
            publish()
            if (config.notifyProgress) notifierProvider()?.notifyCompleted(finished)
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
            if (config.notifyProgress) notifierProvider()?.notifyFailed(failed)
        } finally {
            jobs.remove(id)
            activeIds.remove(id)
            // 任务结束（完成/失败/暂停/取消）后速度归零，避免 UI 停在最后一个瞬时值。
            speedMeter.reset(id)
        }
    }

    private suspend fun runSystem(record: DownloadRecord, config: DownloadConfig): DownloadRecord {
        val downloader = SystemDownloader(context)
        val subPath = "olfact/" + DownloadPaths.plannedName(record.spec)
        // 进程重启后 DownloadManager 里的任务还在：复用 id，避免重复下载
        val existingId = record.systemDownloadId
        val systemId = if (existingId != null && downloader.progressOf(existingId) != null) {
            existingId
        } else {
            downloader.enqueue(record.spec, config, subPath)
        }
        var current = record.copy(systemDownloadId = systemId, updatedAt = now())
        store.upsert(current)
        publish()

        // 系统里的任务可能被清理/丢失：progressOf 返回 null 时不能无限 continue，
        // 否则这条记录会永远停在「下载中」，每秒轮询一次直到进程结束。
        var missingPolls = 0
        while (true) {
            delay(POLL_INTERVAL_MS)
            if (store.get(record.id)?.status == DownloadStatus.CANCELED) {
                downloader.cancel(systemId)
                throw CancellationException("已取消")
            }
            val progress = downloader.progressOf(systemId)
            if (progress == null) {
                missingPolls++
                if (missingPolls >= MAX_MISSING_POLLS) {
                    throw IOException("系统下载任务已丢失（可能被系统清理）")
                }
                continue
            }
            missingPolls = 0
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

    /**
     * 进度写盘节流：UI 最多 5 次/秒，磁盘最多 800ms 一次，通知同样按写盘节奏更新。
     *
     * 终态不经过这里：完成/失败/暂停会走 [publish]，因此 UI 一定收得到最后一条状态，
     * 不会出现「卡在 99%」。
     */
    private fun pushProgress(record: DownloadRecord) {
        val now = now()
        // 速度只用于展示，不进持久化记录（进程重启后重新估算）。
        speedMeter.sample(record.id, record.bytesDownloaded, now)
        val last = lastProgressPush[record.id] ?: 0L
        val persist = now - last >= PROGRESS_PERSIST_INTERVAL_MS
        if (persist) {
            lastProgressPush[record.id] = now
            store.upsert(record)
        }
        if (now - lastUiPush >= UI_PUSH_INTERVAL_MS) {
            lastUiPush = now
            _items.value = store.all().map { if (it.id == record.id) record else it }
                .sortedByDescending { it.createdAt }
        }
        if (persist && configProvider().notifyProgress) notifierProvider()?.notifyProgress(record)
    }

    private fun publish() {
        _items.value = store.all().sortedByDescending { it.createdAt }
    }

    private fun chooseEngine(spec: DownloadSpec, config: DownloadConfig): DownloadEngine = when {
        spec.kind == DownloadKind.HLS -> DownloadEngine.M3U8
        spec.kind == DownloadKind.PROGRESSIVE && config.preferSystemDownloader -> DownloadEngine.SYSTEM
        else -> DownloadEngine.CHUNKED
    }

    private fun now() = System.currentTimeMillis()

    /** 主动触发一次队列处理：**会绕过 WorkManager 的网络/充电约束**，只在显式"立即下载"时用。 */
    fun kick() {
        scope.launch { runCatching { processQueue() } }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 1_000L
        private const val PROGRESS_PERSIST_INTERVAL_MS = 800L
        private const val UI_PUSH_INTERVAL_MS = 200L

        /** 系统任务连续查不到这么多次（≈5s）就判定丢失，避免无限轮询。 */
        private const val MAX_MISSING_POLLS = 5
    }
}
