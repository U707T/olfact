package cn.olfact.download.notify
import cn.olfact.download.DownloadRecord
class DownloadNotifier {
    fun cancel(id: String) = Unit
    fun notifyProgress(record: DownloadRecord, force: Boolean = false) = Unit
    fun notifyCompleted(record: DownloadRecord) = Unit
    fun notifyFailed(record: DownloadRecord) = Unit
}
