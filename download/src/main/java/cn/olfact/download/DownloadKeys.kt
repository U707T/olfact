package cn.olfact.download

import cn.olfact.core.api.SettingsKey

/**
 * 下载设置的唯一 key 定义。
 *
 * 之前设置页写 DataStore（`K.DL_*`），队列读 `olfact_download` 私有 SharedPreferences，
 * 用户在界面上关掉「仅 Wi-Fi」实际仍被限制。现在 app 的 `K.DL_*` 直接引用这里，
 * [DownloadSettings] 也优先读写同一个 [cn.olfact.core.api.SettingsRepository]。
 */
object DownloadKeys {
    val THREADS = SettingsKey("dl.threads", 4)
    val CONCURRENT = SettingsKey("dl.concurrent", 2)
    val WIFI_ONLY = SettingsKey("dl.wifi_only", false)
    val NOTIFY = SettingsKey("dl.notify", true)
    val AUTO_RENAME = SettingsKey("dl.auto_rename", true)
    val M3U8_MERGE = SettingsKey("dl.m3u8_merge", true)
    val CHARGING_ONLY = SettingsKey("dl.charging_only", false)
    /** 0 = 用 [ChunkPlanner.DEFAULT_CHUNK_SIZE]。 */
    val CHUNK_SIZE = SettingsKey("dl.chunk_size", 0L)
    val AD_FILTER = SettingsKey("dl.ad_filter", true)

    /**
     * 单文件优先交给系统 DownloadManager。
     *
     * 之前这个开关只存在于 [DownloadConfig] 里、永远是默认值 true，
     * 结果「单任务线程数 / 分块大小」对普通文件完全不起作用（只有 M3U8 用得上）。
     */
    val PREFER_SYSTEM = SettingsKey("dl.prefer_system", true)
}
