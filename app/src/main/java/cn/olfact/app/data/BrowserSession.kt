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
 * 不变式：只要会话被界面使用，就至少有一个标签页。首页不是标签页，但底栏的
 * 「1」与原版一致，标签页里也不会出现「0 个标签」和角标 1 打架的情况。
 *
 * 「启动时恢复标签」= always/manual 时把上次的标签写进 prefs，冷启动读回。
 */
object BrowserSession {

    private const val KEY = "tabs"
    private const val SELECTED_KEY = "tabs_selected"

    /** 与原版 web 内核的 TabManager 对齐：最多 24 个，超出淘汰最早的标签。 */
    const val MAX_TABS = 24
    private var nextId = 1L
    private val restoredOrigins = mutableMapOf<Pair<Long, String>, Long>()

    val tabs = mutableListOf<Tab>()
    var currentId: Long? = null
        private set

    /**
     * 打开标签页。
     *
     * [selectNew] = false 用于「在后台标签页打开」：只新增标签，不抢当前页。
     * 之前所有调用都会改 currentId，导致地址栏显示 A、会话却指向 B。
     */
    fun open(
        url: String?,
        title: String = "",
        incognito: Boolean = false,
        selectNew: Boolean = true
    ): Tab {
        while (tabs.size >= MAX_TABS) {
            // 先淘汰最早的、且不是当前正在看的标签
            val victim = tabs.firstOrNull { it.id != currentId } ?: tabs.first()
            tabs.remove(victim)
        }
        val tab = Tab(nextId++, url.orEmpty(), title, incognito)
        tabs.add(tab)
        if (selectNew) currentId = tab.id
        return tab
    }

    /**
     * 首页搜索 / 最近访问：直接在当前标签里导航，避免每搜一次就多一个标签。
     * 没有标签时先建一个。
     */
    fun openInCurrent(url: String?, title: String = ""): Tab {
        val tab = current() ?: return open(url, title)
        if (url != null) tab.url = url
        if (title.isNotBlank()) tab.title = title
        return tab
    }

    /**
     * 新建标签页。
     *
     * [reuseBlank] = true（默认）时，当前标签本来就是空白页就直接复用，避免隐式路径
     * （外部链接、冷启动）叠出一串「新标签页」。用户**显式**点「新建窗口」时传 false，
     * 因为原版就是每次都开一个新窗口，复用它会让用户觉得「点了没反应」。
     */
    fun openBlank(reuseBlank: Boolean = true): Tab {
        val tab = current()
        if (reuseBlank && tab != null && tab.url.isBlank() && !tab.incognito) return tab
        return open(null)
    }

    /** 外部链接/书签：有空白当前页就复用，否则新开标签。 */
    fun openOrReuseBlank(url: String, title: String = ""): Tab {
        val tab = current()
        if (tab != null && tab.url.isBlank() && !tab.incognito) {
            tab.url = url
            if (title.isNotBlank()) tab.title = title
            return tab
        }
        return open(url, title)
    }

    /** 启动/兜底：保证至少有一个标签页。 */
    fun ensureTab(): Tab = current() ?: open(null)

    fun select(id: Long) {
        if (tabs.any { it.id == id }) currentId = id
    }

    fun current(): Tab? = tabs.firstOrNull { it.id == currentId } ?: tabs.lastOrNull()

    /** 只给单测用：清空会话状态（生产代码不要调用）。 */
    @androidx.annotation.VisibleForTesting
    internal fun resetForTest() {
        tabs.clear()
        currentId = null
        nextId = 1L
        restoredOrigins.clear()
    }

    fun close(id: Long) {
        val idx = tabs.indexOfFirst { it.id == id }
        if (idx < 0) return
        tabs.removeAt(idx)
        if (currentId == id) {
            currentId = tabs.getOrNull(idx.coerceAtMost(tabs.size - 1))?.id
        }
        // 原版至少保留一个窗口：关掉最后一个时补一个空白页。
        if (tabs.isEmpty()) currentId = open(null).id
    }

    fun closeOthers(id: Long) {
        val keep = tabs.firstOrNull { it.id == id } ?: return
        tabs.clear()
        tabs.add(keep)
        currentId = keep.id
    }

    fun closeAll() {
        tabs.clear()
        currentId = open(null).id
    }

    /**
     * 落盘用的标签列表：**排除无痕标签**。
     *
     * 无痕窗口的 URL 不该出现在 SharedPreferences 里，否则「无痕」只是不写历史，
     * 下次冷启动还能从 prefs 把地址读出来。
     */
    internal fun persistable(): List<Tab> = tabs.filterNot { it.incognito }

    fun save(context: Context) {
        val saved = if (AppSettings.get(K.RESTORE_TABS) == "never") emptyList() else persistable()
        val arr = JSONArray()
        saved.forEach { t ->
            arr.put(JsonStore.objOf("id" to t.id, "url" to t.url, "title" to t.title, "incognito" to false))
        }
        JsonStore.writeArray(context, KEY, arr)
        val selected = saved.firstOrNull { it.id == currentId } ?: saved.lastOrNull()
        JsonStore.writeArray(context, SELECTED_KEY, JSONArray().apply { selected?.let { put(it.id) } })
    }

    /** 「启动时恢复标签」：always 自动恢复；manual 由用户在标签页里手动恢复。 */
    fun restoreIfNeeded(context: Context) {
        val mode = AppSettings.get(K.RESTORE_TABS)
        if (mode == "always") restore(context)
    }

    /** 上次落盘的标签数量（没有则为 0）。 */
    fun savedCount(context: Context): Int =
        if (AppSettings.get(K.RESTORE_TABS) == "never") 0 else JsonStore.readArray(context, KEY).length()

    /**
     * 恢复上次的标签。
     *
     * 与旧实现的区别：不再因为「启动时已经有 1 个空白标签」就直接返回；
     * 只有一个空白页时会先替换掉它，避免手动恢复永远无效。
     *
     * @return 实际恢复的标签数量。
     */
    fun restore(context: Context): Int {
        if (AppSettings.get(K.RESTORE_TABS) == "never") return 0
        val arr = JsonStore.readArray(context, KEY)
        val saved = (0 until arr.length()).mapNotNull { i ->
            val o: JSONObject = arr.optJSONObject(i) ?: return@mapNotNull null
            if (o.optBoolean("incognito", false)) return@mapNotNull null
            Tab(o.optLong("id", 0L), o.optString("url"), o.optString("title"))
        }
        val selected = JsonStore.readArray(context, SELECTED_KEY).optLong(0, -1L)
        return mergeRestored(saved, selected)
    }

    internal fun mergeRestored(saved: List<Tab>, selectedId: Long?): Int {
        if (saved.isEmpty()) return 0
        val onlyBlank = tabs.size <= 1 && tabs.all { it.url.isBlank() && !it.incognito }
        val previous = currentId
        if (onlyBlank) {
            tabs.clear()
            currentId = null
        }
        restoredOrigins.entries.removeAll { (origin, id) -> tabs.none { it.id == id && it.url == origin.second } }
        var added = 0
        for (savedTab in saved) {
            if (tabs.size >= MAX_TABS) break
            if (savedTab.incognito) continue
            val origin = savedTab.id to savedTab.url
            if (restoredOrigins[origin] != null) continue
            val existing = tabs.firstOrNull { it.id == savedTab.id }
            if (existing != null && existing.url == savedTab.url && !existing.incognito) continue
            val id = savedTab.id.takeIf { it > 0 && it < Long.MAX_VALUE && existing == null } ?: nextId
            tabs.add(savedTab.copy(id = id))
            restoredOrigins[origin] = id
            if (id >= nextId) nextId = id + 1
            added++
        }
        if (tabs.isEmpty()) open(null)
        currentId = when {
            !onlyBlank && tabs.any { it.id == previous } -> previous
            tabs.any { it.id == selectedId } -> selectedId
            else -> tabs.lastOrNull()?.id
        }
        return added
    }
}
