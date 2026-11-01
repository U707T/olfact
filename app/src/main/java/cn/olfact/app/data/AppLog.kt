package cn.olfact.app.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 进程内环形日志。崩溃与网络日志页读它；写满 300 条后丢最旧的。
 *
 * 不写文件：本机排查够用，且避免申请存储权限。
 * 线程：写入可能来自后台线程（下载 / GM 桥），条目与监听器都由 [lock] 保护；
 * 监听器回调在锁外执行，避免回调里再读日志造成死锁。
 */
object AppLog {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class Entry(val at: Long, val level: Level, val tag: String, val message: String) {
        fun time(): String = formatTime(at)
    }

    /** 共享 formatter：日志页一次要渲染几百条，不能每条都 new 一个。 */
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private fun formatTime(at: Long): String = synchronized(timeFormat) {
        timeFormat.format(Date(at))
    }

    private const val CAPACITY = 300
    private val lock = Any()
    private val entries = ArrayDeque<Entry>()
    private val listeners = mutableListOf<() -> Unit>()

    fun log(level: Level, tag: String, message: String) {
        val snapshot = synchronized(lock) {
            entries.addFirst(Entry(System.currentTimeMillis(), level, tag, message))
            while (entries.size > CAPACITY) entries.removeLast()
            listeners.toList()
        }
        snapshot.forEach { runCatching { it() } }
    }

    fun d(tag: String, message: String) = log(Level.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(Level.INFO, tag, message)
    fun w(tag: String, message: String) = log(Level.WARN, tag, message)
    fun e(tag: String, message: String) = log(Level.ERROR, tag, message)

    fun all(): List<Entry> = synchronized(lock) { entries.toList() }

    fun clear() {
        val snapshot = synchronized(lock) {
            entries.clear()
            listeners.toList()
        }
        snapshot.forEach { runCatching { it() } }
    }

    fun observe(listener: () -> Unit) {
        synchronized(lock) { listeners.add(listener) }
    }

    fun unobserve(listener: () -> Unit) {
        synchronized(lock) { listeners.remove(listener) }
    }
}
