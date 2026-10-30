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

/** 书签。持久化到 SharedPreferences，最多几百条，够用且没有 Room 的构建成本。 */
object BookmarkStore {

    private const val KEY = "bookmarks"
    private var nextId = 1L
    val items = mutableListOf<Bookmark>()

    fun init(context: Context) {
        if (items.isNotEmpty()) return
        val arr = JsonStore.readArray(context, KEY)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optLong("id", nextId)
            items.add(Bookmark(id, o.optString("title"), o.optString("url"), o.optString("folder", "默认"), o.optLong("at")))
            if (id >= nextId) nextId = id + 1
        }
    }

    fun add(context: Context, title: String, url: String, folder: String = "默认"): Bookmark {
        val b = Bookmark(nextId++, title.ifBlank { url }, url, folder)
        items.add(0, b)
        persist(context)
        return b
    }

    fun remove(context: Context, id: Long) {
        items.removeAll { it.id == id }
        persist(context)
    }

    fun all(): List<Bookmark> = items.toList()

    private fun persist(context: Context) {
        val arr = org.json.JSONArray()
        items.forEach { b ->
            arr.put(JsonStore.objOf("id" to b.id, "title" to b.title, "url" to b.url, "folder" to b.folder, "at" to b.createdAt))
        }
        JsonStore.writeArray(context, KEY, arr)
    }
}

/** 历史。超过「历史记录限制」时从尾部截断。 */
object HistoryStore {

    private const val KEY = "history"
    val items = mutableListOf<HistoryEntry>()

    fun init(context: Context) {
        if (items.isNotEmpty()) return
        val arr = JsonStore.readArray(context, KEY)
        for (i in 0 until arr.length()) {
            val o: JSONObject = arr.optJSONObject(i) ?: continue
            items.add(HistoryEntry(o.optString("title"), o.optString("url"), o.optLong("at")))
        }
    }

    fun record(context: Context, title: String, url: String) {
        if (url.isBlank() || url.startsWith("about:")) return
        items.removeAll { it.url == url }
        items.add(0, HistoryEntry(title.ifBlank { url }, url))
        val limit = runCatching { AppSettings.get(K.HISTORY_LIMIT).toInt() }.getOrDefault(1000)
        while (items.size > limit) items.removeAt(items.size - 1)
        persist(context)
    }

    fun remove(context: Context, url: String) {
        items.removeAll { it.url == url }
        persist(context)
    }

    fun clear(context: Context) {
        items.clear()
        persist(context)
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

    private fun persist(context: Context) {
        val arr = org.json.JSONArray()
        items.forEach { e -> arr.put(JsonStore.objOf("title" to e.title, "url" to e.url, "at" to e.visitedAt)) }
        JsonStore.writeArray(context, KEY, arr)
    }
}
