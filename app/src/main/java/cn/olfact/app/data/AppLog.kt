package cn.olfact.app.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 进程内环形日志。崩溃与网络日志页读它；写满 300 条后丢最旧的。
 *
 * 不写文件：本机排查够用，且避免申请存储权限。
 */
object AppLog {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class Entry(val at: Long, val level: Level, val tag: String, val message: String) {
        fun time(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(at))
    }

    private const val CAPACITY = 300
    private val entries = ArrayDeque<Entry>()
    private val listeners = mutableListOf<() -> Unit>()

    fun log(level: Level, tag: String, message: String) {
        entries.addFirst(Entry(System.currentTimeMillis(), level, tag, message))
        while (entries.size > CAPACITY) entries.removeLast()
        listeners.forEach { it() }
    }

    fun d(tag: String, message: String) = log(Level.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(Level.INFO, tag, message)
    fun w(tag: String, message: String) = log(Level.WARN, tag, message)
    fun e(tag: String, message: String) = log(Level.ERROR, tag, message)

    fun all(): List<Entry> = entries.toList()

    fun clear() {
        entries.clear()
        listeners.forEach { it() }
    }

    fun observe(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun unobserve(listener: () -> Unit) {
        listeners.remove(listener)
    }
}
