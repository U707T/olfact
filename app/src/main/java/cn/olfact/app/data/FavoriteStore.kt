package cn.olfact.app.data

import android.content.Context
import org.json.JSONObject

/**
 * 播放页「收藏」：存媒体地址与标题，列表页读它。
 *
 * 线程：内存列表用 [lock] 保护（播放页、备份恢复、列表页可能同时访问）。
 */
object FavoriteStore {

    private const val KEY = "favorites"
    private var nextId = 1L
    private val lock = Any()
    val items = mutableListOf<Favorite>()

    data class Favorite(val id: Long, val title: String, val url: String, val at: Long = System.currentTimeMillis())

    fun init(context: Context) {
        synchronized(lock) {
            if (items.isNotEmpty()) return
            val arr = JsonStore.readArray(context, KEY)
            for (i in 0 until arr.length()) {
                val o: JSONObject = arr.optJSONObject(i) ?: continue
                val id = o.optLong("id", nextId)
                items.add(Favorite(id, o.optString("title"), o.optString("url"), o.optLong("at")))
                if (id >= nextId) nextId = id + 1
            }
        }
    }

    fun add(context: Context, title: String, url: String): Boolean {
        synchronized(lock) {
            if (items.any { it.url == url }) return false
            items.add(0, Favorite(nextId++, title.ifBlank { url }, url))
        }
        persist(context)
        return true
    }

    fun remove(context: Context, id: Long) {
        synchronized(lock) { items.removeAll { it.id == id } }
        persist(context)
    }

    /** 撤销删除：原样放回。 */
    fun restore(context: Context, favorite: Favorite) {
        synchronized(lock) {
            if (items.any { it.id == favorite.id }) return
            items.add(0, favorite)
        }
        persist(context)
    }

    /**
     * 批量导入（备份恢复）：保留收藏时间，按时间倒序合并。
     *
     * 旧实现逐条 [add]：时间被重置成「现在」，而且每条都插到头部，
     * 恢复后的收藏顺序与备份完全相反。
     */
    fun importAll(context: Context, list: List<Favorite>): Int {
        var added = 0
        synchronized(lock) {
            val known = items.mapTo(mutableSetOf()) { it.url }
            list.forEach { f ->
                if (f.url.isBlank() || !known.add(f.url)) return@forEach
                val id = if (items.any { it.id == f.id }) nextId++ else f.id
                items.add(Favorite(id, f.title.ifBlank { f.url }, f.url, f.at))
                if (id >= nextId) nextId = id + 1
                added++
            }
            if (added > 0) items.sortByDescending { it.at }
        }
        if (added > 0) persist(context)
        return added
    }

    /** 进程退到后台时补一次落盘。 */
    fun flush(context: Context) = persist(context)

    private fun persist(context: Context) {
        val arr = org.json.JSONArray()
        synchronized(lock) {
            items.forEach { f ->
                arr.put(JsonStore.objOf("id" to f.id, "title" to f.title, "url" to f.url, "at" to f.at))
            }
        }
        JsonStore.writeArray(context, KEY, arr)
    }
}
