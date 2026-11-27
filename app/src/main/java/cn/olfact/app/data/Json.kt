package cn.olfact.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 极简 JSON 持久化。数据量都在「几十条」级别，用 SharedPreferences + org.json 足够，
 * 不为此引入 Room 或序列化库（体积预算优先）。
 */
internal object JsonStore {

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("olfact_data", Context.MODE_PRIVATE)

    fun readArray(context: Context, key: String): JSONArray =
        runCatching { JSONArray(prefs(context).getString(key, "[]")) }.getOrElse { JSONArray() }

    fun writeArray(context: Context, key: String, array: JSONArray) {
        prefs(context).edit().putString(key, array.toString()).apply()
    }

    /** 仅恢复需要同步落盘回执；普通浏览写入仍使用 apply。必须在后台调用。 */
    fun writeArrayConfirmed(context: Context, key: String, array: JSONArray) {
        if (!prefs(context).edit().putString(key, array.toString()).commit()) {
            throw IOException("保存 $key 失败，请检查存储空间后重试恢复")
        }
    }

    fun objOf(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().apply {
        pairs.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) }
    }
}

/**
 * 落盘调度：序列化 + JSON 组装放后台线程，同一 key 的连续写入做 debounce。
 *
 * 历史记录每翻一页就会写一次；旧实现在主线程把整张表拼成 JSON（1000 条约 100 KB），
 * 掉帧明显。这里只保留「最后一次」快照，主线程只做一次 `toList()`。
 */
internal object JsonPersist {

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "olfact-json-persist").apply { isDaemon = true }
    }

    private val pending = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val queueLock = Any()

    fun schedule(
        context: Context,
        key: String,
        delayMs: Long = 400,
        snapshot: () -> JSONArray
    ) {
        val ctx = context.applicationContext
        synchronized(queueLock) {
            pending.remove(key)?.cancel(false)
            pending[key] = scheduler.schedule({
                runCatching { JsonStore.writeArray(ctx, key, snapshot()) }
            }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * 恢复专用：取消尚未开始的旧快照，与已开始的写入串行，等待 commit 的真实回执。
     * 必须从后台线程调用；失败抛给 BackupPipeline，不能把排程成功当成恢复成功。
     */
    fun persistNow(context: Context, key: String, snapshot: () -> JSONArray) {
        val ctx = context.applicationContext
        val receipt = synchronized(queueLock) {
            pending.remove(key)?.cancel(false)
            scheduler.submit { JsonStore.writeArrayConfirmed(ctx, key, snapshot()) }
        }
        try {
            receipt.get()
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            throw IOException("保存 $key 失败：${cause.message ?: "写入错误"}", cause)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("等待 $key 落盘被中断；部分数据可能已写入", e)
        }
    }

    /** 进程退出前尽量把待写数据刷掉（SharedPreferences 自身仍是异步 apply）。 */
    fun flushNow(context: Context, key: String, snapshot: () -> JSONArray) {
        pending.remove(key)?.cancel(false)
        runCatching { JsonStore.writeArray(context.applicationContext, key, snapshot()) }
    }
}
