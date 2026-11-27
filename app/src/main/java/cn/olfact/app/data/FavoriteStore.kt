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
    private val data = mutableListOf<Favorite>()
    val items: List<Favorite>
        get() = synchronized(lock) { data.toList() }

    data class Favorite(
        val id: Long,
        val title: String,
        val url: String,
        val at: Long = System.currentTimeMillis(),
        val sourcePage: String? = null,
        val mediaType: String? = null,
        val mimeType: String? = null
    )

    fun init(context: Context) {
        synchronized(lock) {
            if (data.isNotEmpty()) return
            val arr = JsonStore.readArray(context, KEY)
            for (i in 0 until arr.length()) {
                val o: JSONObject = arr.optJSONObject(i) ?: continue
                val id = o.optLong("id", nextId)
                data.add(
                    Favorite(
                        id = id,
                        title = o.optString("title"),
                        url = o.optString("url"),
                        at = o.optLong("at"),
                        sourcePage = safeFavoriteSourcePage(o.optString("sourcePage").takeIf { it.isNotBlank() && it != "null" }),
                        mediaType = o.optString("mediaType").takeIf { it.isNotBlank() && it != "null" },
                        mimeType = o.optString("mimeType").takeIf { it.isNotBlank() && it != "null" }
                    )
                )
                if (id >= nextId) nextId = id + 1
            }
        }
    }

    fun add(
        context: Context,
        title: String,
        url: String,
        sourcePage: String? = null,
        mediaType: String? = null,
        mimeType: String? = null
    ): Boolean {
        synchronized(lock) {
            if (data.any { it.url == url }) return false
            data.add(
                0,
                Favorite(
                    id = nextId++,
                    title = title.ifBlank { url },
                    url = url,
                    sourcePage = safeFavoriteSourcePage(sourcePage),
                    mediaType = mediaType?.takeIf { it.isNotBlank() },
                    mimeType = mimeType?.takeIf { it.isNotBlank() }
                )
            )
        }
        persist(context)
        return true
    }

    fun remove(context: Context, id: Long) {
        synchronized(lock) { data.removeAll { it.id == id } }
        persist(context)
    }

    /** 撤销删除：原样放回。 */
    fun restore(context: Context, favorite: Favorite) {
        synchronized(lock) {
            if (data.any { it.id == favorite.id }) return
            data.add(0, favorite)
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
            val known = data.mapTo(mutableSetOf()) { it.url }
            list.forEach { f ->
                if (f.url.isBlank() || !known.add(f.url)) return@forEach
                val id = if (data.any { it.id == f.id }) nextId++ else f.id
                data.add(
                    Favorite(
                        id = id,
                        title = f.title.ifBlank { f.url },
                        url = f.url,
                        at = f.at,
                        sourcePage = safeFavoriteSourcePage(f.sourcePage),
                        mediaType = f.mediaType?.takeIf { it.isNotBlank() },
                        mimeType = f.mimeType?.takeIf { it.isNotBlank() }
                    )
                )
                if (id >= nextId) nextId = id + 1
                added++
            }
            if (added > 0) data.sortByDescending { it.at }
        }
        persistNow(context)
        return added
    }

    /** 进程退到后台时补一次落盘。 */
    fun flush(context: Context) = persist(context, delayMs = 0)

    private fun persist(context: Context, delayMs: Long = 150) {
        JsonPersist.schedule(context, KEY, delayMs) { snapshotArray() }
    }

    private fun persistNow(context: Context) {
        JsonPersist.persistNow(context, KEY) { snapshotArray() }
    }

    private fun snapshotArray(): org.json.JSONArray {
        val snapshot = items
        return org.json.JSONArray().apply {
            snapshot.forEach { f ->
                put(JsonStore.objOf(
                    "id" to f.id,
                    "title" to f.title,
                    "url" to f.url,
                    "at" to f.at,
                    "sourcePage" to f.sourcePage,
                    "mediaType" to f.mediaType,
                    "mimeType" to f.mimeType
                ))
            }
        }
    }
}

/** Store only a safe page path for recovery; never persist URL credentials or query tokens. */
internal fun safeFavoriteSourcePage(source: String?): String? = runCatching {
    val uri = java.net.URI(source?.trim().orEmpty())
    val scheme = uri.scheme?.lowercase() ?: return@runCatching null
    val rawAuthority = uri.rawAuthority ?: return@runCatching null
    val authority = if (uri.rawUserInfo != null) rawAuthority.substringAfterLast('@') else rawAuthority
    if (authority.isBlank()) return@runCatching null
    "$scheme://$authority${uri.rawPath.orEmpty()}"
}.getOrNull()
