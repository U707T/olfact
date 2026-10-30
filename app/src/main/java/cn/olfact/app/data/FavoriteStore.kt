package cn.olfact.app.data

import android.content.Context
import org.json.JSONObject

/** 播放页「收藏」：存媒体地址与标题，列表页读它。 */
object FavoriteStore {

    private const val KEY = "favorites"
    private var nextId = 1L
    val items = mutableListOf<Favorite>()

    data class Favorite(val id: Long, val title: String, val url: String, val at: Long = System.currentTimeMillis())

    fun init(context: Context) {
        if (items.isNotEmpty()) return
        val arr = JsonStore.readArray(context, KEY)
        for (i in 0 until arr.length()) {
            val o: JSONObject = arr.optJSONObject(i) ?: continue
            val id = o.optLong("id", nextId)
            items.add(Favorite(id, o.optString("title"), o.optString("url"), o.optLong("at")))
            if (id >= nextId) nextId = id + 1
        }
    }

    fun add(context: Context, title: String, url: String): Boolean {
        if (items.any { it.url == url }) return false
        items.add(0, Favorite(nextId++, title.ifBlank { url }, url))
        persist(context)
        return true
    }

    fun remove(context: Context, id: Long) {
        items.removeAll { it.id == id }
        persist(context)
    }

    private fun persist(context: Context) {
        val arr = org.json.JSONArray()
        items.forEach { f ->
            arr.put(JsonStore.objOf("id" to f.id, "title" to f.title, "url" to f.url, "at" to f.at))
        }
        JsonStore.writeArray(context, KEY, arr)
    }
}
