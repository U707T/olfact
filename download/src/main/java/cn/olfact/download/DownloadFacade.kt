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
        // 只交给 WorkManager：约束（仅 WiFi / 充电）由它保证，不能在这里 kick 绕过。
        // WorkManager 起不来（未初始化 / 被 ROM 禁用）时才退回前台队列，否则任务会一直排队。
        val scheduled = DownloadWorker.schedule(appContext, settings.snapshot())
        if (!scheduled) scheduleFallback()
        return record
    }

    fun cancel(id: String) = queue.cancel(id)
    fun pause(id: String) = queue.pause(id)
    fun resume(id: String) = queue.resume(id)
    fun retry(id: String) = queue.retry(id)

    /**
     * 删除任务记录。
     *
     * [deleteFile] = true 时连成品文件一起删；false 只删记录。默认 true 与旧调用点
     * 「详情页删除」的语义一致，列表/清空这类「只删记录」的路径必须显式传 false。
     */
    fun remove(id: String, deleteFile: Boolean = true) = queue.remove(id, deleteFile)
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
