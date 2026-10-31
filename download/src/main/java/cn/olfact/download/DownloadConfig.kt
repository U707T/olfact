package cn.olfact.download

import cn.olfact.download.m3u8.AdFilterConfig

/** 队列行为配置。由设置页（B5）读写，[DownloadSettings] 负责落到 SharedPreferences。 */
data class DownloadConfig(
    val wifiOnly: Boolean = false,
    val chargingOnly: Boolean = false,
    val maxConcurrentTasks: Int = 2,
    val threadsPerTask: Int = 4,
    val chunkSizeBytes: Long = ChunkPlanner.DEFAULT_CHUNK_SIZE,
    val adFilter: AdFilterConfig = AdFilterConfig(),
    val notifyProgress: Boolean = true,
    /** 单文件优先交给系统 DownloadManager（通知/断点/队列全托管）。 */
    val preferSystemDownloader: Boolean = true,
)
