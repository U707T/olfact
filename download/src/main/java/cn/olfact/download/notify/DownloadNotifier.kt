package cn.olfact.download.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import cn.olfact.download.DownloadRecord
import cn.olfact.download.R
import cn.olfact.download.util.Files

/**
 * 下载通知：进度（节流）、完成、失败。点击进入下载列表。
 * [contentActivity] 由 app 注入（download 模块不认识界面类）。
 */
class DownloadNotifier(
    private val context: Context,
    private val contentActivity: Class<*>? = null,
) {

    private val manager = NotificationManagerCompat.from(context)

    init {
        createChannel()
    }

    /**
     * 每个任务各自节流。之前 lastProgressAt 是 companion 的单个值：
     * 同时下 4 个文件时，只有最先到的那条能刷新通知，其余三条一直停在 0%。
     */
    private val lastProgressAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun notifyProgress(record: DownloadRecord, force: Boolean = false) {
        if (!shouldNotify(record)) return
        val now = System.currentTimeMillis()
        val last = lastProgressAt[record.id] ?: 0L
        if (!force && now - last < PROGRESS_INTERVAL_MS) return
        lastProgressAt[record.id] = now

        val text = buildString {
            append(record.progressPercent).append("%")
            if (record.totalBytes > 0) append(" · ").append(Files.humanSize(record.bytesDownloaded))
                .append(" / ").append(Files.humanSize(record.totalBytes))
            if (record.segmentsTotal > 0) append(" · ").append(record.segmentsDone).append('/').append(record.segmentsTotal).append(" 片")
        }
        val notification = base(record)
            .setContentTitle(record.spec.fileName.ifBlank { "下载中" })
            .setContentText(text)
            .setProgress(100, record.progressPercent, record.totalBytes <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        safeNotify(record.id.hashCode(), notification)
    }

    fun notifyCompleted(record: DownloadRecord) {
        lastProgressAt.remove(record.id)
        val notification = base(record)
            .setContentTitle("下载完成")
            .setContentText(record.spec.fileName.ifBlank { "文件已保存" })
            .setOngoing(false)
            .setAutoCancel(true)
            .setProgress(0, 0, false)
            .build()
        safeNotify(record.id.hashCode(), notification)
    }

    fun notifyFailed(record: DownloadRecord) {
        lastProgressAt.remove(record.id)
        val notification = base(record)
            .setContentTitle("下载失败")
            .setContentText(record.error ?: record.spec.fileName.ifBlank { "未知错误" })
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        safeNotify(record.id.hashCode(), notification)
    }

    fun cancel(recordId: String) {
        lastProgressAt.remove(recordId)
        runCatching { manager.cancel(recordId.hashCode()) }
    }

    private fun shouldNotify(record: DownloadRecord): Boolean = true

    private fun base(record: DownloadRecord): NotificationCompat.Builder {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        val intent = contentIntent(record)
        if (intent != null) builder.setContentIntent(intent)
        return builder
    }

    private fun contentIntent(record: DownloadRecord): PendingIntent? {
        val activity = contentActivity ?: return null
        val intent = Intent(context, activity).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_DOWNLOAD_ID, record.id)
        }
        return PendingIntent.getActivity(
            context,
            record.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun safeNotify(id: Int, notification: android.app.Notification) {
        runCatching { manager.notify(id, notification) }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.download_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.download_channel_desc)
            setShowBadge(false)
        }
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "olfact_downloads"
        const val EXTRA_DOWNLOAD_ID = "cn.olfact.download.EXTRA_ID"
        private const val PROGRESS_INTERVAL_MS = 800L
    }
}
