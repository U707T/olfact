package cn.olfact.app.data

import android.content.Context
import org.json.JSONObject

data class Bookmark(
    val id: Long,
    val title: String,
    val url: String,
    val folder: String = "默认",
    val createdAt: Long = System.currentTimeMillis()
)

data class HistoryEntry(
    val title: String,
    val url: String,
    val visitedAt: Long = System.currentTimeMillis()
)

/**
 * 书签。持久化到 SharedPreferences，最多几百条，够用且没有 Room 的构建成本。
 *
 * 线程：内存列表由 [lock] 保护；落盘在 [JsonPersist] 的后台线程完成，
 * 主线程只负责更新内存 + 提交快照，不再序列化整张表。
 */
object BookmarkStore {

    private const val KEY = "bookmarks"
    private val lock = Any()
    private val data = mutableListOf<Bookmark>()
    private var nextId = 1L

    /** 只读快照。 */
    val items: List<Bookmark>
        get() = synchronized(lock) { data.toList() }

    fun init(context: Context) {
        synchronized(lock) {
            if (data.isNotEmpty()) return
            val arr = JsonStore.readArray(context, KEY)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optLong("id", nextId)
                data.add(Bookmark(id, o.optString("title"), o.optString("url"), o.optString("folder", "默认"), o.optLong("at")))
                if (id >= nextId) nextId = id + 1
            }
        }
    }

    fun add(context: Context, title: String, url: String, folder: String = "默认"): Bookmark {
        val b = synchronized(lock) {
            Bookmark(nextId++, title.ifBlank { url }, url, folder).also { data.add(0, it) }
        }
        persist(context)
        return b
    }

    fun remove(context: Context, id: Long) {
        synchronized(lock) { data.removeAll { it.id == id } }
        persist(context)
    }

    fun all(): List<Bookmark> = items

    private fun persist(context: Context) {
        JsonPersist.schedule(context, KEY, delayMs = 150) {
            val snapshot = synchronized(lock) { data.toList() }
            org.json.JSONArray().apply {
                snapshot.forEach { b ->
                    put(JsonStore.objOf("id" to b.id, "title" to b.title, "url" to b.url, "folder" to b.folder, "at" to b.createdAt))
                }
            }
        }
    }
}

/** 历史。超过「历史记录限制」时从尾部截断。 */
object HistoryStore {

    private const val KEY = "history"
    private val lock = Any()
    private val data = mutableListOf<HistoryEntry>()

    /** 只读快照。 */
    val items: List<HistoryEntry>
        get() = synchronized(lock) { data.toList() }

    fun init(context: Context) {
        synchronized(lock) {
            if (data.isNotEmpty()) return
            val arr = JsonStore.readArray(context, KEY)
            for (i in 0 until arr.length()) {
                val o: JSONObject = arr.optJSONObject(i) ?: continue
                data.add(HistoryEntry(o.optString("title"), o.optString("url"), o.optLong("at")))
            }
        }
    }

    fun record(context: Context, title: String, url: String) {
        if (url.isBlank() || url.startsWith("about:")) return
        val limit = runCatching { AppSettings.get(K.HISTORY_LIMIT).toInt() }.getOrDefault(1000)
        synchronized(lock) {
            data.removeAll { it.url == url }
            data.add(0, HistoryEntry(title.ifBlank { url }, url))
            while (data.size > limit) data.removeAt(data.size - 1)
        }
        // 800ms debounce：连续翻页只落盘最后一次，避免每页一次整表序列化。
        persist(context, delayMs = 800)
    }

    fun remove(context: Context, url: String) {
        synchronized(lock) { data.removeAll { it.url == url } }
        persist(context, delayMs = 150)
    }

    fun clear(context: Context) {
        synchronized(lock) { data.clear() }
        persist(context, delayMs = 0)
    }

    /** 设置里把「历史记录限制」调小后立即裁剪一次，不用等下一次记录。 */
    fun trim(context: Context) {
        val limit = runCatching { AppSettings.get(K.HISTORY_LIMIT).toInt() }.getOrDefault(1000)
        synchronized(lock) {
            while (data.size > limit) data.removeAt(data.size - 1)
        }
        persist(context, delayMs = 150)
    }

    /** 首页「最近访问」用：按 host 去重，最多 n 条。 */
    fun recent(n: Int): List<HistoryEntry> {
        val seen = HashSet<String>()
        return items.filter { e ->
            val host = runCatching { java.net.URI(e.url).host }.getOrNull() ?: e.url
            seen.add(host)
        }.take(n)
    }

    fun search(query: String, limit: Int = 5): List<HistoryEntry> =
        items.filter { it.title.contains(query, true) || it.url.contains(query, true) }.take(limit)

    private fun persist(context: Context, delayMs: Long = 800) {
        JsonPersist.schedule(context, KEY, delayMs) {
            val snapshot = synchronized(lock) { data.toList() }
            org.json.JSONArray().apply {
                snapshot.forEach { e ->
                    put(JsonStore.objOf("title" to e.title, "url" to e.url, "at" to e.visitedAt))
                }
            }
        }
    }
}
