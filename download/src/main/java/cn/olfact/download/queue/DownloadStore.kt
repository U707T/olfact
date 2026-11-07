package cn.olfact.download.queue

import cn.olfact.download.DownloadRecord
import cn.olfact.download.DownloadStatus
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * 队列持久化接口。默认实现是 [JsonDownloadStore]（原子写 JSON 文件）；
 * B1 的 Room 单库落地后可以换实现，调用方（DownloadQueue）不需要改。
 */
interface DownloadStore {
    fun all(): List<DownloadRecord>
    fun get(id: String): DownloadRecord?
    fun upsert(record: DownloadRecord)
    fun remove(id: String)

    /**
     * 批量删除（「清空已下载」）：默认逐条删，落盘实现应覆盖成一次写。
     * 逐条删 N 条记录＝重写 N 次队列文件、推 N 次列表，几百条历史时会明显卡。
     */
    fun removeAll(ids: Collection<String>) {
        ids.forEach { remove(it) }
    }
    fun nextQueued(): DownloadRecord?

    fun activeCount(): Int = all().count { it.status.isActive }

    fun markAllRunningAsPaused(reason: String = "进程重启"): Int {
        var count = 0
        all().filter { it.status == DownloadStatus.RUNNING }.forEach {
            upsert(it.withStatus(DownloadStatus.PAUSED, reason))
            count++
        }
        return count
    }
}

/** 原子写的 JSON 文件队列。写入 temp + rename，掉电也不会留半个文件。 */
class JsonDownloadStore(private val file: File) : DownloadStore {

    private val lock = Any()
    private var cache: MutableMap<String, DownloadRecord>? = null
    private val revision = AtomicLong(0)

    override fun all(): List<DownloadRecord> = synchronized(lock) {
        load().values.sortedByDescending { it.createdAt }
    }

    override fun get(id: String): DownloadRecord? = synchronized(lock) { load()[id] }

    override fun upsert(record: DownloadRecord) = synchronized(lock) {
        load()[record.id] = record
        persist()
    }

    override fun remove(id: String) = synchronized(lock) {
        load().remove(id)
        persist()
    }

    override fun removeAll(ids: Collection<String>) = synchronized(lock) {
        val map = load()
        var changed = false
        ids.forEach { if (map.remove(it) != null) changed = true }
        if (changed) persist()
    }

    override fun nextQueued(): DownloadRecord? = synchronized(lock) {
        load().values
            .filter { it.status == DownloadStatus.QUEUED }
            .minByOrNull { it.createdAt }
    }

    val currentRevision: Long get() = revision.get()

    private fun load(): MutableMap<String, DownloadRecord> {
        cache?.let { return it }
        val map = LinkedHashMap<String, DownloadRecord>()
        if (file.exists() && file.length() > 0) {
            val text = runCatching { file.readText() }.getOrDefault("")
            DownloadRecordCodec.decodeAll(text).forEach { map[it.id] = it }
        }
        cache = map
        return map
    }

    private fun persist() {
        val snapshot = cache ?: return
        val payload = DownloadRecordCodec.encodeAll(snapshot.values.toList())
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        runCatching {
            tmp.writeText(payload)
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
        revision.incrementAndGet()
    }
}

/** 单测与"内存模式"用。 */
class InMemoryDownloadStore(initial: List<DownloadRecord> = emptyList()) : DownloadStore {
    private val lock = Any()
    private val map = LinkedHashMap<String, DownloadRecord>().apply {
        initial.forEach { put(it.id, it) }
    }

    override fun all(): List<DownloadRecord> = synchronized(lock) { map.values.toList() }
    override fun get(id: String): DownloadRecord? = synchronized(lock) { map[id] }
    override fun upsert(record: DownloadRecord) = synchronized(lock) { map[record.id] = record }
    override fun remove(id: String) { synchronized(lock) { map.remove(id) } }
    override fun nextQueued(): DownloadRecord? = synchronized(lock) {
        map.values.filter { it.status == DownloadStatus.QUEUED }.minByOrNull { it.createdAt }
    }
}
