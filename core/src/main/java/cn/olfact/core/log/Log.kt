package cn.olfact.core.log

import android.util.Log as AndroidLog
import cn.olfact.core.BuildConfig

/**
 * 统一日志（Timber 风格的最小实现，不引第三方）。
 *
 * 用法：`Log.d("tab created")` / `Log.e(t, "load failed")`。
 * debug 包全量输出，release 只留 warn 以上；可在启动时覆写 [enabled]。
 */
object Log {
    const val TAG = "olfact"

    /** 单条日志超过 logcat 上限会被截断，按 4K 分片。 */
    private const val CHUNK = 4000

    @Volatile
    var enabled: Boolean = BuildConfig.DEBUG

    fun v(message: String) = write(AndroidLog.VERBOSE, message, null)
    fun d(message: String) = write(AndroidLog.DEBUG, message, null)
    fun i(message: String) = write(AndroidLog.INFO, message, null)
    fun w(message: String) = write(AndroidLog.WARN, message, null)
    fun e(message: String) = write(AndroidLog.ERROR, message, null)

    fun d(t: Throwable, message: String) = write(AndroidLog.DEBUG, message, t)
    fun i(t: Throwable, message: String) = write(AndroidLog.INFO, message, t)
    fun w(t: Throwable, message: String) = write(AndroidLog.WARN, message, t)
    fun e(t: Throwable, message: String) = write(AndroidLog.ERROR, message, t)

    private fun write(priority: Int, message: String, t: Throwable?) {
        if (!enabled && priority < AndroidLog.WARN) return
        if (message.length <= CHUNK) {
            if (t == null) AndroidLog.println(priority, TAG, message) else AndroidLog.println(priority, TAG, "$message\n${AndroidLog.getStackTraceString(t)}")
            return
        }
        var start = 0
        while (start < message.length) {
            val end = (start + CHUNK).coerceAtMost(message.length)
            AndroidLog.println(priority, TAG, message.substring(start, end))
            start = end
        }
        if (t != null) AndroidLog.println(priority, TAG, AndroidLog.getStackTraceString(t))
    }
}
