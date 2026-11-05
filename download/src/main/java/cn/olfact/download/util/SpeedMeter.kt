package cn.olfact.download.util

/**
 * 下载速度估算。
 *
 * 队列每拿到一次进度就喂一次「累计字节 + 时间戳」，这里用相邻两次采样求瞬时速度，
 * 再做一次指数平滑（旧值权重 2/3），避免 UI 上的数字每刷新一次就乱跳。
 *
 * 纯逻辑、无 Android 依赖，便于单测。
 */
class SpeedMeter {

    private data class Sample(val bytes: Long, val atMs: Long)

    private val samples = HashMap<String, Sample>()
    private val speeds = HashMap<String, Long>()

    /**
     * 记录一次采样并返回平滑后的速度（字节/秒）。
     *
     * - 首次采样只记时间，返回 0；
     * - 两次采样间隔小于 [MIN_INTERVAL_MS] 时沿用上次结果（进度回调可能非常密集）；
     * - 字节数回退（重试/换源）时按 0 处理，不产生负数速度。
     *
     * 并发：多个任务会同时上报进度，这里用 [Synchronized] 保护内部表。
     */
    @Synchronized
    fun sample(id: String, bytes: Long, atMs: Long): Long {
        val previous = samples[id]
        if (previous == null) {
            samples[id] = Sample(bytes, atMs)
            return 0L
        }
        val elapsed = atMs - previous.atMs
        if (elapsed < MIN_INTERVAL_MS) return speeds[id] ?: 0L

        val delta = (bytes - previous.bytes).coerceAtLeast(0L)
        val instant = delta * 1000L / elapsed
        val smoothed = speeds[id]?.let { (it * 2 + instant) / 3 } ?: instant
        samples[id] = Sample(bytes, atMs)
        speeds[id] = smoothed
        return smoothed
    }

    @Synchronized
    fun speedOf(id: String): Long = speeds[id] ?: 0L

    @Synchronized
    fun reset(id: String) {
        samples.remove(id)
        speeds.remove(id)
    }

    @Synchronized
    fun clear() {
        samples.clear()
        speeds.clear()
    }

    companion object {
        /** 进度回调通常 250–1000ms 一次；小于这个间隔不更新，避免抖动。 */
        const val MIN_INTERVAL_MS = 500L
    }
}
