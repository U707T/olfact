package cn.olfact.download

enum class DownloadEngine(val wireName: String) {
    /** 系统 DownloadManager（断点/通知/队列全托管） */
    SYSTEM("system"),

    /** 自研多线程分块 */
    CHUNKED("chunked"),

    /** m3u8 分片 + remux */
    M3U8("m3u8");

    companion object {
        fun fromWire(name: String?): DownloadEngine =
            entries.firstOrNull { it.wireName.equals(name, ignoreCase = true) } ?: SYSTEM
    }
}

data class DownloadRecord(
    val id: String,
    val spec: DownloadSpec,
    val engine: DownloadEngine,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = -1L,
    val segmentsDone: Int = 0,
    val segmentsTotal: Int = 0,
    val error: String? = null,
    val filePath: String? = null,
    val systemDownloadId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val progressPercent: Int
        get() = when {
            status == DownloadStatus.COMPLETED -> 100
            totalBytes > 0 -> ((bytesDownloaded.coerceAtLeast(0) * 100) / totalBytes).toInt().coerceIn(0, 100)
            segmentsTotal > 0 -> ((segmentsDone * 100) / segmentsTotal).coerceIn(0, 100)
            else -> 0
        }

    val isRunning: Boolean get() = status == DownloadStatus.RUNNING

    fun withStatus(newStatus: DownloadStatus, message: String? = null): DownloadRecord =
        copy(status = newStatus, error = message ?: if (newStatus == DownloadStatus.FAILED) error else null,
            updatedAt = System.currentTimeMillis())

    fun withProgress(bytes: Long, total: Long = totalBytes): DownloadRecord =
        copy(bytesDownloaded = bytes.coerceAtLeast(0), totalBytes = if (total > 0) total else totalBytes,
            updatedAt = System.currentTimeMillis())

    fun withSegments(done: Int, total: Int): DownloadRecord =
        copy(segmentsDone = done, segmentsTotal = total, updatedAt = System.currentTimeMillis())
}
