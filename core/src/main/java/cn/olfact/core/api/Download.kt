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

    /**
     * 入队一次下载。
     *
     * 网页里点下载时，光有 URL 往往不够：
     * - [referer] / [pageUrl]：防盗链站点没有 Referer 会直接 403；
     * - [userAgent]：部分站点按 UA 返回不同内容；
     * - [mimeType]：URL 没有扩展名时（`/download?id=1`）靠它决定文件后缀；
     * - [headers]：嗅探到的 Cookie / Authorization 等需要原样透传。
     *
     * 默认参数保证旧的「只给 URL」调用方式继续可用。
     */
    fun enqueue(
        url: String,
        name: String? = null,
        pageUrl: String = "",
        referer: String = "",
        userAgent: String? = null,
        mimeType: String? = null,
        headers: Map<String, String> = emptyMap()
    )

    fun pause(id: String)
    fun resume(id: String)
    fun remove(id: String, deleteFile: Boolean)
    fun clearFinished()
    val changes: Flow<Unit>
}
