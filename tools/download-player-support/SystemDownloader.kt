package cn.olfact.download

import android.content.Context

/** DownloadManager is intentionally outside the standalone JVM coverage. */
class SystemDownloader(context: Context) {
    fun enqueue(spec: DownloadSpec, config: DownloadConfig, path: String): Long = error("Requires Android DownloadManager")
    fun cancel(id: Long): Unit = error("Requires Android DownloadManager")
    fun progressOf(id: Long): SystemProgress? = error("Requires Android DownloadManager")
    fun localUri(id: Long): String? = error("Requires Android DownloadManager")
}
data class SystemProgress(val isComplete: Boolean, val isFailed: Boolean, val totalBytes: Long,
    val downloadedBytes: Long, val reason: String? = null)
