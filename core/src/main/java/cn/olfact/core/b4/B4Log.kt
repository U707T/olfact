package cn.olfact.core.b4

/**
 * B4 三块子系统共用的极简日志门面。
 *
 * 为什么不用 `android.util.Log` 直连：拦截/脚本/备份的核心逻辑要能在纯 JVM 单测里跑，
 * `android.util.Log` 在单测里是桩（默认返回 0，但不同 AGP 版本偶有 "not mocked"），
 * 统一走这里，App 启动时由 `B4Log.installAndroidSink()` 接管。
 */
object B4Log {

    @Volatile
    var enabled: Boolean = true

    @Volatile
    var sink: (level: Int, tag: String, message: String, error: Throwable?) -> Unit =
        { level, tag, message, error ->
            val flag = when (level) {
                0 -> "D"
                1 -> "W"
                else -> "E"
            }
            System.err.println("[$flag/$tag] $message")
            error?.printStackTrace()
        }

    fun d(tag: String, message: String) {
        if (enabled) sink(0, tag, message, null)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        if (enabled) sink(1, tag, message, error)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        sink(2, tag, message, error)
    }
}
