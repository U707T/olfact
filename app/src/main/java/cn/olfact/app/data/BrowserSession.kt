package cn.olfact.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Tab(
    val id: Long,
    var url: String = "",
    var title: String = "",
    val incognito: Boolean = false
)

/**
 * 标签页会话。跨 Fragment 共享，进程内单例。
 *
 * 「启动时恢复标签」= always/manual 时把上次的标签写进 prefs，冷启动读回。
 */
object BrowserSession {

    private const val KEY = "tabs"
    private var nextId = 1L

    val tabs = mutableListOf<Tab>()
    var currentId: Long? = null
        private set

    fun open(url: String?, title: String = "", incognito: Boolean = false): Tab {
        val tab = Tab(nextId++, url.orEmpty(), title, incognito)
        tabs.add(tab)
        currentId = tab.id
        return tab
    }

    fun select(id: Long) {
        if (tabs.any { it.id == id }) currentId = id
    }

    fun current(): Tab? = tabs.firstOrNull { it.id == currentId } ?: tabs.lastOrNull()

    fun close(id: Long) {
        val idx = tabs.indexOfFirst { it.id == id }
        if (idx < 0) return
        tabs.removeAt(idx)
        if (currentId == id) {
            currentId = tabs.getOrNull(idx.coerceAtMost(tabs.size - 1))?.id
        }
    }

    fun closeOthers(id: Long) {
        tabs.retainAll { it.id == id }
        currentId = tabs.firstOrNull()?.id
    }

    fun closeAll() {
        tabs.clear()
        currentId = null
    }

    fun save(context: Context) {
        val arr = JSONArray()
        tabs.forEach { t ->
            arr.put(JsonStore.objOf("id" to t.id, "url" to t.url, "title" to t.title, "incognito" to t.incognito))
        }
        JsonStore.writeArray(context, KEY, arr)
    }

    /** 「启动时恢复标签」：always 自动恢复；manual 由用户在标签页里手动恢复。 */
    fun restoreIfNeeded(context: Context) {
        val mode = AppSettings.get(K.RESTORE_TABS)
        if (mode == "always") restore(context)
    }

    /** 上次落盘的标签数量（没有则为 0）。 */
    fun savedCount(context: Context): Int = JsonStore.readArray(context, KEY).length()

    fun restore(context: Context) {
        if (tabs.isNotEmpty()) return
        val arr = JsonStore.readArray(context, KEY)
        for (i in 0 until arr.length()) {
            val o: JSONObject = arr.optJSONObject(i) ?: continue
            val id = o.optLong("id", nextId)
            val tab = Tab(id, o.optString("url"), o.optString("title"), o.optBoolean("incognito"))
            tabs.add(tab)
            if (id >= nextId) nextId = id + 1
        }
        currentId = tabs.lastOrNull()?.id
    }
}
