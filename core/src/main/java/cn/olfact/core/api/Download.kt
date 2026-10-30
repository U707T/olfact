package cn.olfact.core.api

import kotlinx.coroutines.flow.Flow

enum class DownloadState { QUEUED, RUNNING, PAUSED, MERGING, DONE, FAILED }

/** 下载面板用的任务快照。 */
data class DownloadTask(
    val id: String,
    val name: String,
    val url: String,
    val state: DownloadState = DownloadState.QUEUED,
    val totalBytes: Long = 0L,
    val doneBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long = 0L,
    val pageUrl: String = "",
    val savePath: String = "",
    val error: String? = null
) {
    val progress: Int
        get() = if (totalBytes <= 0) 0 else ((doneBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
}

/** B3 实现，B5 只读 + 触发操作。 */
interface DownloadCenter {
    fun tasks(): List<DownloadTask>
    fun enqueue(url: String, name: String? = null, pageUrl: String = "")
    fun pause(id: String)
    fun resume(id: String)
    fun remove(id: String, deleteFile: Boolean)
    fun clearFinished()
    val changes: Flow<Unit>
}
