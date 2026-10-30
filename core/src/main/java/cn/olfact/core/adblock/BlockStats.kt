package cn.olfact.core.adblock

import cn.olfact.core.b4.B4Log
import cn.olfact.core.b4.StoreFiles
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Calendar
import java.util.Locale

@Serializable
data class BlockStatsSnapshot(
    val day: String = "",
    val today: Long = 0L,
    val total: Long = 0L,
    val byType: Map<String, Long> = emptyMap()
)

/**
 * 拦截计数。**按天滚动 + 批量落盘**：每 20 次拦截才写一次盘，
 * 避免每个子资源都做一次 IO 把首屏拖慢。
 */
class BlockStats(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
) {

    @Serializable
    private data class Persisted(
        val day: String = "",
        val today: Long = 0L,
        val total: Long = 0L,
        val byType: Map<String, Long> = emptyMap()
    )

    private var day: String = dayKey(clock())
    private var today: Long = 0L
    private var total: Long = 0L
    private val byType = HashMap<String, Long>()
    private var sinceFlush = 0

    init {
        restore()
    }

    @Synchronized
    private fun restore() {
        val text = StoreFiles.readText(file) ?: return
        runCatching { json.decodeFromString(Persisted.serializer(), text) }
            .onSuccess {
                total = it.total
                byType.clear()
                byType.putAll(it.byType)
                if (it.day == day) {
                    today = it.today
                } else {
                    day = it.day
                    today = it.today
                    rollDayIfNeeded()
                }
            }
            .onFailure { B4Log.w("BlockStats", "stats.json 损坏，重新计数", it) }
    }

    @Synchronized
    fun record(type: ResourceType) {
        rollDayIfNeeded()
        today++
        total++
        byType[type.optionName] = (byType[type.optionName] ?: 0L) + 1
        if (++sinceFlush >= FLUSH_EVERY) flushLocked()
    }

    @Synchronized
    fun snapshot(): BlockStatsSnapshot {
        rollDayIfNeeded()
        return BlockStatsSnapshot(day, today, total, HashMap(byType))
    }

    @Synchronized
    fun flush() = flushLocked()

    /** 设置页"清空统计"。 */
    @Synchronized
    fun reset() {
        today = 0
        total = 0
        byType.clear()
        day = dayKey(clock())
        sinceFlush = 0
        flushLocked()
    }

    private fun flushLocked() {
        sinceFlush = 0
        runCatching {
            val payload = Persisted(day, today, total, HashMap(byType))
            StoreFiles.writeAtomic(file, json.encodeToString(Persisted.serializer(), payload))
        }.onFailure { B4Log.w("BlockStats", "写入统计失败", it) }
    }

    private fun rollDayIfNeeded() {
        val now = dayKey(clock())
        if (now != day) {
            day = now
            today = 0
        }
    }

    companion object {
        private const val FLUSH_EVERY = 20

        fun dayKey(timestamp: Long): String {
            val c = Calendar.getInstance()
            c.timeInMillis = timestamp
            return String.format(
                Locale.US,
                "%04d-%02d-%02d",
                c.get(Calendar.YEAR),
                c.get(Calendar.MONTH) + 1,
                c.get(Calendar.DAY_OF_MONTH)
            )
        }
    }
}
