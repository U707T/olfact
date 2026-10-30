package cn.olfact.download

import android.content.Context
import cn.olfact.download.notify.DownloadNotifier
import cn.olfact.download.queue.DownloadQueue
import cn.olfact.download.queue.DownloadWorker
import cn.olfact.download.queue.JsonDownloadStore
import java.io.File

/**
 * download 模块对外唯一入口。
 * app 侧只需：
 * ```
 * val facade = DownloadFacade.get(context).apply { contentActivity = DownloadListActivity::class.java }
 * facade.enqueue(DownloadSpec(url = ..., fileName = ...))
 * ```
 */
class DownloadFacade private constructor(private val appContext: Context) {

    val settings = DownloadSettings(appContext)

    @Volatile
    private var notifier: DownloadNotifier = DownloadNotifier(appContext, null)

    /** 下载列表 Activity（由 app 注入）。改了之后新通知才会带跳转。 */
    var contentActivity: Class<*>? = null
        set(value) {
            field = value
            notifier = DownloadNotifier(appContext, value)
        }

    private val store = JsonDownloadStore(File(appContext.filesDir, "download/queue.json"))

    val queue: DownloadQueue = DownloadQueue(
        context = appContext,
        store = store,
        configProvider = { settings.snapshot() },
        notifierProvider = { notifier },
    )

    fun enqueue(spec: DownloadSpec, engine: DownloadEngine? = null): DownloadRecord {
        val record = queue.enqueue(spec, engine)
        DownloadWorker.schedule(appContext, settings.snapshot())
        queue.kick()
        return record
    }

    fun cancel(id: String) = queue.cancel(id)
    fun pause(id: String) = queue.pause(id)
    fun resume(id: String) = queue.resume(id)
    fun retry(id: String) = queue.retry(id)
    fun remove(id: String) = queue.remove(id)
    fun refresh() = queue.refresh()

    /** WorkManager 被用户禁用/未初始化时的兜底：前台直接跑。 */
    fun scheduleFallback() {
        queue.kick()
    }

    companion object {
        @Volatile
        private var instance: DownloadFacade? = null

        fun get(context: Context): DownloadFacade =
            instance ?: synchronized(this) {
                instance ?: DownloadFacade(context.applicationContext).also { instance = it }
            }
    }
}
