package cn.olfact.download.queue

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cn.olfact.download.DownloadConfig
import kotlinx.coroutines.CancellationException

/**
 * 队列执行者。约束（WiFi / 充电）由 WorkManager 保证，
 * 并发数由 [DownloadQueue.processQueue] 内部的信号量控制。
 *
 * 长下载必须跑在前台服务里：普通 Worker 约 10 分钟就会被系统掐断，
 * 大文件会反复重试、进度归零。这里用 `setForeground` 把执行窗口延长到下载结束。
 */
class DownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val facade = cn.olfact.download.DownloadFacade.get(applicationContext)
        // 前台服务在部分 ROM/低电量模式下可能被拒；失败时退回普通 Worker，不能让下载直接失败。
        runCatching { setForeground(createForegroundInfo()) }
        return try {
            facade.queue.reconcile()
            facade.queue.processQueue()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (runAttemptCount < MAX_RETRY) Result.retry() else Result.failure()
        }
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "下载服务", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "下载队列在后台运行"
                        setShowBadge(false)
                    }
                )
            }
        }
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
        val pending = launch?.let {
            android.app.PendingIntent.getActivity(
                ctx, 0, it,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
        }
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在下载")
            .setContentText("下载队列在后台运行")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply { pending?.let { setContentIntent(it) } }
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val UNIQUE_NAME = "olfact-download-queue"
        private const val MAX_RETRY = 3
        private const val CHANNEL_ID = "olfact_download_service"
        private const val NOTIFICATION_ID = 0x0F01

        fun schedule(context: Context, config: DownloadConfig) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (config.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                )
                .setRequiresCharging(config.chargingOnly)
                .build()
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(constraints)
                .build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    UNIQUE_NAME,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    request,
                )
            }
        }
    }
}
