package cn.olfact.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment

data class SystemProgress(
    val status: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val reason: String? = null,
) {
    val isComplete: Boolean get() = status == DownloadManager.STATUS_SUCCESSFUL
    val isFailed: Boolean get() = status == DownloadManager.STATUS_FAILED
    val isRunning: Boolean
        get() = status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_PAUSED
}

/**
 * 单文件走系统 DownloadManager：断点、通知、省电策略全由系统托管。
 * 缺点是无法自定义分块/请求头玩法，所以 m3u8 与需要特殊头的场景仍走自研引擎。
 */
class SystemDownloader(private val context: Context) {

    private val manager: DownloadManager
        get() = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    fun enqueue(spec: DownloadSpec, config: DownloadConfig, subPath: String): Long {
        val request = DownloadManager.Request(Uri.parse(spec.url))
            .setTitle(spec.fileName.ifBlank { "video" })
            .setDescription(spec.pageTitle ?: spec.pageUrl ?: "")
            .setAllowedOverMetered(!config.wifiOnly)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                if (subPath.startsWith("olfact")) subPath else "olfact/$subPath",
            )

        spec.mimeType?.let { request.setMimeType(it) }
        val ua = spec.userAgent ?: spec.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
        if (!ua.isNullOrBlank()) request.addRequestHeader("User-Agent", ua)
        spec.referer?.takeIf { it.isNotBlank() }?.let { request.addRequestHeader("Referer", it) }
        spec.headers.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank() &&
                !key.equals("User-Agent", true) && !key.equals("Referer", true)
            ) {
                request.addRequestHeader(key, value)
            }
        }
        return manager.enqueue(request)
    }

    fun progressOf(systemId: Long): SystemProgress? {
        val query = DownloadManager.Query().setFilterById(systemId)
        return runCatching {
            manager.query(query).use { cursor ->
                if (!cursor.moveToFirst()) return null
                fun column(name: String): Long {
                    val index = cursor.getColumnIndex(name)
                    return if (index >= 0) cursor.getLong(index) else -1L
                }
                SystemProgress(
                    status = column(DownloadManager.COLUMN_STATUS).toInt(),
                    downloadedBytes = column(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR).coerceAtLeast(0),
                    totalBytes = column(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                    reason = cursor.getColumnIndex(DownloadManager.COLUMN_REASON).takeIf { it >= 0 }
                        ?.let { cursor.getInt(it) }?.toString(),
                )
            }
        }.getOrNull()
    }

    fun localUri(systemId: Long): String? = runCatching { manager.getUriForDownloadedFile(systemId)?.toString() }.getOrNull()

    fun cancel(systemId: Long) {
        runCatching { manager.remove(systemId) }
    }
}
