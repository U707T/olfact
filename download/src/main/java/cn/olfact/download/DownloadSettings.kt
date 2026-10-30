package cn.olfact.download

import android.content.Context
import cn.olfact.download.m3u8.AdFilterConfig

class DownloadSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var wifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI, true)
        set(value) = prefs.edit().putBoolean(KEY_WIFI, value).apply()

    var chargingOnly: Boolean
        get() = prefs.getBoolean(KEY_CHARGING, false)
        set(value) = prefs.edit().putBoolean(KEY_CHARGING, value).apply()

    var maxConcurrentTasks: Int
        get() = prefs.getInt(KEY_CONCURRENT, 2).coerceIn(1, 4)
        set(value) = prefs.edit().putInt(KEY_CONCURRENT, value.coerceIn(1, 4)).apply()

    var threadsPerTask: Int
        get() = prefs.getInt(KEY_THREADS, 4).coerceIn(1, 8)
        set(value) = prefs.edit().putInt(KEY_THREADS, value.coerceIn(1, 8)).apply()

    var chunkSizeBytes: Long
        get() = prefs.getLong(KEY_CHUNK, ChunkPlanner.DEFAULT_CHUNK_SIZE)
        set(value) = prefs.edit().putLong(KEY_CHUNK, value.coerceAtLeast(256L * 1024)).apply()

    var adFilterEnabled: Boolean
        get() = prefs.getBoolean(KEY_AD_FILTER, true)
        set(value) = prefs.edit().putBoolean(KEY_AD_FILTER, value).apply()

    var notifyProgress: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY, value).apply()

    fun snapshot(): DownloadConfig = DownloadConfig(
        wifiOnly = wifiOnly,
        chargingOnly = chargingOnly,
        maxConcurrentTasks = maxConcurrentTasks,
        threadsPerTask = threadsPerTask,
        chunkSizeBytes = chunkSizeBytes,
        adFilter = AdFilterConfig(enabled = adFilterEnabled),
        notifyProgress = notifyProgress,
    )

    companion object {
        private const val PREFS = "olfact_download"
        private const val KEY_WIFI = "wifi_only"
        private const val KEY_CHARGING = "charging_only"
        private const val KEY_CONCURRENT = "max_concurrent"
        private const val KEY_THREADS = "threads"
        private const val KEY_CHUNK = "chunk_size"
        private const val KEY_AD_FILTER = "ad_filter"
        private const val KEY_NOTIFY = "notify_progress"
    }
}
