package cn.olfact.app.ui.player

import cn.olfact.core.api.MediaItem

/** In-memory results keyed by the originating tab and page, never by media URL alone. */
object SnifferFeed {
    private const val MAX_ITEMS = 200
    private data class Key(val tabId: Long, val pageUrl: String, val url: String)
    private val byKey = LinkedHashMap<Key, MediaItem>()

    @Synchronized
    fun add(tabId: Long, item: MediaItem) {
        val page = item.pageUrl?.takeIf { it.isNotBlank() } ?: return
        val key = Key(tabId, page, item.url)
        byKey.remove(key)
        byKey[key] = item
        if (byKey.size > MAX_ITEMS) byKey.remove(byKey.keys.first())
    }

    @Synchronized
    fun forPage(tabId: Long, pageUrl: String): List<MediaItem> = byKey.entries
        .filter { (key, _) -> key.tabId == tabId && key.pageUrl == pageUrl }
        .map { it.value }
        .asReversed()

    @Synchronized
    fun find(tabId: Long, pageUrl: String, url: String): MediaItem? = byKey[Key(tabId, pageUrl, url)]

    @Synchronized
    fun clearTab(tabId: Long) {
        byKey.keys.removeAll { it.tabId == tabId }
    }
}
