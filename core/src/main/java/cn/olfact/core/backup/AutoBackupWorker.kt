package cn.olfact.core.backup

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import cn.olfact.core.b4.B4Log
import java.util.concurrent.TimeUnit

/**
 * 定时备份。五档：启动即备份 / 1h / 6h / 12h / 24h（任务书要求）。
 *
 * 真正的备份逻辑不在这里：App 启动时把 [BackupPipeline] 装进 [BackupRuntime.runner]，
 * Worker 只负责触发——这样 Worker 不依赖任何单例初始化顺序，也方便单测。
 */
class AutoBackupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val runner = BackupRuntime.runner
        if (runner == null) {
            B4Log.w(TAG, "备份流水线未装配，跳过本次任务")
            return Result.success()
        }
        val trigger = inputData.getString(KEY_TRIGGER) ?: TRIGGER_PERIODIC
        val result = try {
            runner.run(trigger)
        } catch (t: Throwable) {
            B4Log.e(TAG, "备份任务异常", t)
            BackupResult(success = false, trigger = trigger, error = t.message ?: t.javaClass.simpleName)
        }
        BackupRuntime.lastResult = result
        return when {
            result.success -> Result.success()
            runAttemptCount < MAX_RETRY -> Result.retry()
            else -> Result.failure()
        }
    }

    companion object {
        private const val TAG = "AutoBackup"
        private const val MAX_RETRY = 3

        const val KEY_TRIGGER = "trigger"
        const val TRIGGER_PERIODIC = "periodic"
        const val TRIGGER_MANUAL = "manual"
        const val TRIGGER_BOOT = "boot"
    }
}

/** Worker 与业务之间的唯一耦合点。 */
object BackupRuntime {
    @Volatile
    var runner: BackupRunner? = null

    @Volatile
    var lastResult: BackupResult? = null
}

enum class BackupInterval(val hours: Int, val label: String) {
    ON_START(0, "启动时备份"),
    H1(1, "每 1 小时"),
    H6(6, "每 6 小时"),
    H12(12, "每 12 小时"),
    H24(24, "每 24 小时"),

    /** 设置页的「每周」；WorkManager 周期上限就是 15 分钟起步、没有上限。 */
    WEEK(24 * 7, "每 7 天");

    companion object {
        fun fromHours(hours: Int): BackupInterval = entries.firstOrNull { it.hours == hours } ?: H24
    }
}

class AutoBackupScheduler(private val context: Context) {

    fun schedule(interval: BackupInterval, requireNetwork: Boolean = true) {
        if (interval == BackupInterval.ON_START) {
            cancel()
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (requireNetwork) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
            .build()
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(interval.hours.toLong(), TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .setInputData(workDataOf(AutoBackupWorker.KEY_TRIGGER to AutoBackupWorker.TRIGGER_PERIODIC))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
        B4Log.d("AutoBackup", "定时备份已排程：${interval.label}")
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /** 立即备份一次（设置页"立即备份"）。 */
    fun runNow() {
        val request = OneTimeWorkRequestBuilder<AutoBackupWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(AutoBackupWorker.KEY_TRIGGER to AutoBackupWorker.TRIGGER_MANUAL))
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    fun isScheduled(): Boolean = runCatching {
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(WORK_NAME)
            .get()
            .any { !it.state.isFinished }
    }.getOrDefault(false)

    companion object {
        const val WORK_NAME = "olfact-auto-backup"
    }
}
