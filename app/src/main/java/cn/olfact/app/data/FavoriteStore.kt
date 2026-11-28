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

    enum class SaveResult { ADDED, UPDATED, UNCHANGED }

    fun add(
        context: Context,
        title: String,
        url: String,
        sourcePage: String? = null,
        mediaType: String? = null,
        mimeType: String? = null
    ): Boolean = saveOrEnrich(context, title, url, sourcePage, mediaType, mimeType) == SaveResult.ADDED

    /** 补齐旧收藏的缺失上下文，保留原 ID、收藏时间、排序与已有有效信息。 */
    fun saveOrEnrich(
        context: Context,
        title: String,
        url: String,
        sourcePage: String? = null,
        mediaType: String? = null,
        mimeType: String? = null
    ): SaveResult {
        val result = synchronized(lock) {
            val index = data.indexOfFirst { it.url == url }
            val incoming = Favorite(
                id = 0,
                title = title.ifBlank { url },
                url = url,
                sourcePage = safeFavoriteSourcePage(sourcePage),
                mediaType = mediaType?.takeIf { it.isNotBlank() },
                mimeType = mimeType?.takeIf { it.isNotBlank() }
            )
            if (index >= 0) {
                val original = data[index]
                val enriched = enrich(original, incoming)
                if (enriched == original) return@synchronized SaveResult.UNCHANGED
                data[index] = enriched
                SaveResult.UPDATED
            } else {
                data.add(0, incoming.copy(id = nextId++))
                SaveResult.ADDED
            }
        }
        if (result != SaveResult.UNCHANGED) persist(context)
        return result
    }

    private fun enrich(original: Favorite, incoming: Favorite): Favorite = original.copy(
        title = incoming.title.takeIf {
            (original.title.isBlank() || original.title == original.url) && it.isNotBlank() && it != incoming.url
        } ?: original.title,
        sourcePage = safeFavoriteSourcePage(original.sourcePage) ?: safeFavoriteSourcePage(incoming.sourcePage),
        mediaType = original.mediaType?.takeIf { it.isNotBlank() }
            ?: incoming.mediaType?.takeIf { it.isNotBlank() },
        mimeType = original.mimeType?.takeIf { it.isNotBlank() }
            ?: incoming.mimeType?.takeIf { it.isNotBlank() }
    )

    fun remove(context: Context, id: Long) {
        synchronized(lock) { data.removeAll { it.id == id } }
        persist(context)
    }

    /** 撤销期间同一媒体被重新收藏时只补齐缺失信息，不产生重复项。 */
    fun restore(context: Context, favorite: Favorite) {
        synchronized(lock) {
            val index = data.indexOfFirst { it.url == favorite.url }
            if (index >= 0) {
                data[index] = enrich(data[index], favorite)
            } else {
                if (data.any { it.id == favorite.id }) return
                data.add(0, favorite.copy(sourcePage = safeFavoriteSourcePage(favorite.sourcePage)))
                if (favorite.id >= nextId) nextId = favorite.id + 1
            }
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
            val known = data.mapIndexed { index, item -> item.url to index }.toMap().toMutableMap()
            list.forEach { f ->
                if (f.url.isBlank()) return@forEach
                val existingIndex = known[f.url]
                if (existingIndex != null) {
                    data[existingIndex] = enrich(data[existingIndex], f)
                    return@forEach
                }
                val id = if (data.any { it.id == f.id }) nextId++ else f.id
                known[f.url] = data.size
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
    if (scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return@runCatching null
    if (uri.port < -1 || uri.port > 65535) return@runCatching null
    val rawAuthority = uri.rawAuthority ?: return@runCatching null
    val authority = if (uri.rawUserInfo != null) rawAuthority.substringAfterLast('@') else rawAuthority
    if (authority.isBlank()) return@runCatching null
    "$scheme://$authority${uri.rawPath.orEmpty()}"
}.getOrNull()
