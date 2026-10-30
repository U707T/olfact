package cn.olfact.download.queue

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cn.olfact.download.DownloadConfig
import kotlinx.coroutines.CancellationException

/**
 * 队列执行者。约束（WiFi / 充电）由 WorkManager 保证，
 * 并发数由 [DownloadQueue.processQueue] 内部的信号量控制。
 */
class DownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val facade = cn.olfact.download.DownloadFacade.get(applicationContext)
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

    companion object {
        const val UNIQUE_NAME = "olfact-download-queue"
        private const val MAX_RETRY = 3

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
