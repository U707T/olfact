package cn.olfact.app.data

import android.content.Context
import android.widget.Toast
import cn.olfact.core.api.DownloadCenter
import cn.olfact.core.api.DownloadState
import cn.olfact.core.api.DownloadTask
import cn.olfact.download.DownloadFacade
import cn.olfact.download.DownloadStatus
import cn.olfact.download.DownloadSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** `core/api` 下载面板 → B3 的 `DownloadFacade`。 */
class DownloadAdapter(
    private val facade: DownloadFacade,
    private val scope: CoroutineScope,
    private val appContext: Context
) : DownloadCenter {

    override fun tasks(): List<DownloadTask> =
        facade.queue.items.value.map { it.toTask(speed = facade.queue.speedOf(it.id)) }

    override fun enqueue(
        url: String,
        name: String?,
        pageUrl: String,
        referer: String,
        userAgent: String?,
        mimeType: String?,
        headers: Map<String, String>
    ) {
        val file = name?.ifBlank { null }
            ?: url.substringBefore('?').substringAfterLast('/').ifBlank { "download-${UUID.randomUUID()}" }
        scope.launch {
            runCatching {
                facade.enqueue(
                    DownloadSpec(
                        url = url,
                        fileName = file,
                        mimeType = mimeType,
                        userAgent = userAgent,
                        // 页面地址优先当 Referer；没有页面地址时不要瞎填成下载地址本身。
                        referer = referer.ifBlank { pageUrl }.ifBlank { null },
                        pageUrl = pageUrl.ifBlank { null },
                        headers = headers
                    )
                )
            }.onFailure { e ->
                // 之前这里是静默的：入队失败（目录不可写、队列文件坏、spec 非法）用户只看到「没反应」。
                android.util.Log.w("olfact", "添加下载失败: $url", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "添加下载失败：${e.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun pause(id: String) = facade.pause(id)

    override fun resume(id: String) = facade.resume(id)

    /**
     * 删除记录：删除的是**记录**，文件是否保留由 [deleteFile] 决定。
     * 旧实现把 false 映射成 cancel()，结果记录没删、状态还变成「失败」。
     */
    override fun remove(id: String, deleteFile: Boolean) {
        facade.remove(id, deleteFile)
    }

    /** 「清空已下载」= 只删记录，绝不删用户已经下载好的文件。 */
    override fun clearFinished() {
        facade.queue.items.value.filter { it.status.isTerminal }.forEach {
            facade.remove(it.id, deleteFile = false)
        }
    }

    override val changes: Flow<Unit> = facade.queue.items.map { }
}

internal fun cn.olfact.download.DownloadRecord.toTask(speed: Long = 0L): DownloadTask = DownloadTask(
    id = id,
    name = spec.fileName,
    url = spec.url,
    state = when (status) {
        DownloadStatus.QUEUED -> DownloadState.QUEUED
        DownloadStatus.RUNNING -> DownloadState.RUNNING
        DownloadStatus.PAUSED -> DownloadState.PAUSED
        DownloadStatus.COMPLETED -> DownloadState.DONE
        DownloadStatus.FAILED -> DownloadState.FAILED
        DownloadStatus.CANCELED -> DownloadState.FAILED
    },
    totalBytes = totalBytes.coerceAtLeast(0L),
    doneBytes = bytesDownloaded.coerceAtLeast(0L),
    speedBytesPerSec = speed,
    createdAt = createdAt,
    finishedAt = if (status.isTerminal) updatedAt else 0L,
    pageUrl = spec.pageUrl.orEmpty(),
    savePath = filePath.orEmpty(),
    error = error
)
