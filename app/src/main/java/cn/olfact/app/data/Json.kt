package cn.olfact.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
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

    fun schedule(
        context: Context,
        key: String,
        delayMs: Long = 400,
        snapshot: () -> JSONArray
    ) {
        val ctx = context.applicationContext
        pending.remove(key)?.cancel(false)
        val future = scheduler.schedule({
            pending.remove(key)
            runCatching {
                val array = snapshot()
                JsonStore.writeArray(ctx, key, array)
            }
        }, delayMs, TimeUnit.MILLISECONDS)
        pending[key] = future
    }

    /** 进程退出前尽量把待写数据刷掉（SharedPreferences 自身仍是异步 apply）。 */
    fun flushNow(context: Context, key: String, snapshot: () -> JSONArray) {
        pending.remove(key)?.cancel(false)
        runCatching { JsonStore.writeArray(context.applicationContext, key, snapshot()) }
    }
}
