package cn.olfact.player

import kotlin.math.abs

/** 纯计算，便于单元测试；不碰任何 Android 类。 */
object PlayerMath {

    fun formatDuration(positionMs: Long): String {
        if (positionMs <= 0L) return "00:00"
        val totalSeconds = positionMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    /** 双击快进/快退后的目标位置，夹在 [0, duration] 内。 */
    fun seekTarget(positionMs: Long, durationMs: Long, deltaMs: Long): Long {
        val raw = positionMs + deltaMs
        val upper = if (durationMs > 0) durationMs else Long.MAX_VALUE
        return raw.coerceIn(0L, upper)
    }

    /** 是否显示"跳片头"：离片头还有一段距离才有意义。 */
    fun shouldShowSkipIntro(positionMs: Long, skipIntroMs: Long): Boolean =
        skipIntroMs > 0 && positionMs in 0 until skipIntroMs

    /** 是否显示"跳片尾"：剩余时间小于片尾长度。 */
    fun shouldShowSkipOutro(positionMs: Long, durationMs: Long, skipOutroMs: Long): Boolean =
        skipOutroMs > 0 && durationMs > 0 && durationMs - positionMs in 1 until skipOutroMs

    fun progressPercent(positionMs: Long, durationMs: Long): Int {
        if (durationMs <= 0L) return 0
        return ((positionMs.coerceAtLeast(0L) * 100) / durationMs).toInt().coerceIn(0, 100)
    }

    /** 长按倍速结束后恢复到哪个速度：取进入前记录的值。 */
    fun restoreSpeed(remembered: Float): Float = PlayerPrefs.clampSpeed(remembered)

    fun nearlyEquals(a: Float, b: Float, epsilon: Float = 0.01f): Boolean = abs(a - b) <= epsilon

    /** 播放器上报的 aspect；异常值直接丢弃，避免 PiP 崩溃。 */
    fun safeAspect(width: Int, height: Int): Pair<Int, Int>? =
        if (width > 0 && height > 0) width to height else null
}
