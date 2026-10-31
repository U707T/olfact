package cn.olfact.download

import android.content.Context
import cn.olfact.core.api.SettingsRepository
import cn.olfact.download.m3u8.AdFilterConfig

/**
 * 下载参数。绑定 [SettingsRepository] 后只认 DataStore 这一份真相；
 * 未绑定（例如 download 模块单独调试）时才回退到模块私有 prefs。
 */
class DownloadSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val repo: SettingsRepository? get() = repository

    var wifiOnly: Boolean
        get() = repo?.get(DownloadKeys.WIFI_ONLY) ?: prefs.getBoolean(KEY_WIFI, false)
        set(value) = putBoolean(DownloadKeys.WIFI_ONLY, KEY_WIFI, value)

    var chargingOnly: Boolean
        get() = repo?.get(DownloadKeys.CHARGING_ONLY) ?: prefs.getBoolean(KEY_CHARGING, false)
        set(value) = putBoolean(DownloadKeys.CHARGING_ONLY, KEY_CHARGING, value)

    var maxConcurrentTasks: Int
        get() = (repo?.get(DownloadKeys.CONCURRENT) ?: prefs.getInt(KEY_CONCURRENT, 2)).coerceIn(1, 5)
        set(value) {
            val v = value.coerceIn(1, 5)
            repo?.set(DownloadKeys.CONCURRENT, v) ?: prefs.edit().putInt(KEY_CONCURRENT, v).apply()
        }

    var threadsPerTask: Int
        get() = (repo?.get(DownloadKeys.THREADS) ?: prefs.getInt(KEY_THREADS, 4)).coerceIn(1, 8)
        set(value) {
            val v = value.coerceIn(1, 8)
            repo?.set(DownloadKeys.THREADS, v) ?: prefs.edit().putInt(KEY_THREADS, v).apply()
        }

    var chunkSizeBytes: Long
        get() {
            val stored = repo?.get(DownloadKeys.CHUNK_SIZE) ?: prefs.getLong(KEY_CHUNK, 0L)
            return if (stored >= MIN_CHUNK) stored else ChunkPlanner.DEFAULT_CHUNK_SIZE
        }
        set(value) {
            val v = value.coerceAtLeast(MIN_CHUNK)
            repo?.set(DownloadKeys.CHUNK_SIZE, v) ?: prefs.edit().putLong(KEY_CHUNK, v).apply()
        }

    var adFilterEnabled: Boolean
        get() = repo?.get(DownloadKeys.AD_FILTER) ?: prefs.getBoolean(KEY_AD_FILTER, true)
        set(value) = putBoolean(DownloadKeys.AD_FILTER, KEY_AD_FILTER, value)

    var notifyProgress: Boolean
        get() = repo?.get(DownloadKeys.NOTIFY) ?: prefs.getBoolean(KEY_NOTIFY, true)
        set(value) = putBoolean(DownloadKeys.NOTIFY, KEY_NOTIFY, value)

    fun snapshot(): DownloadConfig = DownloadConfig(
        wifiOnly = wifiOnly,
        chargingOnly = chargingOnly,
        maxConcurrentTasks = maxConcurrentTasks,
        threadsPerTask = threadsPerTask,
        chunkSizeBytes = chunkSizeBytes,
        adFilter = AdFilterConfig(enabled = adFilterEnabled),
        notifyProgress = notifyProgress,
    )

    private fun putBoolean(key: cn.olfact.core.api.SettingsKey<Boolean>, prefKey: String, value: Boolean) {
        val r = repo
        if (r != null) r.set(key, value) else prefs.edit().putBoolean(prefKey, value).apply()
    }

    companion object {
        private const val PREFS = "olfact_download"
        private const val KEY_WIFI = "wifi_only"
        private const val KEY_CHARGING = "charging_only"
        private const val KEY_CONCURRENT = "max_concurrent"
        private const val KEY_THREADS = "threads"
        private const val KEY_CHUNK = "chunk_size"
        private const val KEY_AD_FILTER = "ad_filter"
        private const val KEY_NOTIFY = "notify_progress"
        private const val MIN_CHUNK = 256L * 1024

        @Volatile
        private var repository: SettingsRepository? = null

        /** 由 app 在 Application.onCreate 里调用一次。 */
        fun bind(settings: SettingsRepository) {
            repository = settings
        }
    }
}
