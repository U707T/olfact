package cn.olfact.app.ui.player

import cn.olfact.core.api.MediaItem
import cn.olfact.core.api.MediaType

/**
 * 嗅探结果缓存。真实实现由 B2 在 `shouldInterceptRequest` 里调 `MediaSniffer.onMediaDetected`，
 * 这里保存成列表供播放页 / 嗅探弹层读取。
 *
 * 线程约定：写入来自 Chromium 的 IO 线程，读取在主线程。所有访问都走 [lock]，
 * 并用 URL 去重 + 容量上限，避免长页面把内存拖垮（旧实现是裸 `mutableListOf`，
 * 并发 add/filter 会抛 `ConcurrentModificationException`，且列表只增不减）。
 */
object SnifferFeed {

    /** 一次浏览会话最多保留的媒体条数；超出后按「最早发现」淘汰。 */
    private const val MAX_ITEMS = 200

    private val lock = Any()
    private val byUrl = LinkedHashMap<String, MediaItem>()

    /** 只读快照，最新的在前。给需要「全部结果」的界面用。 */
    val items: List<MediaItem>
        get() = synchronized(lock) { byUrl.values.toList().asReversed() }

    fun add(item: MediaItem) {
        synchronized(lock) {
            // 重新插入，保证同一 URL 再次命中时移到「最新」位置
            byUrl.remove(item.url)
            byUrl[item.url] = item
            while (byUrl.size > MAX_ITEMS) {
                val oldest = byUrl.keys.firstOrNull() ?: break
                byUrl.remove(oldest)
            }
        }
    }

    fun forPage(pageUrl: String): List<MediaItem> = synchronized(lock) {
        byUrl.values
            .filter { pageUrl.isBlank() || it.pageUrl == pageUrl }
            .asReversed()
    }

    fun all(): List<MediaItem> = items

    fun clear() {
        synchronized(lock) { byUrl.clear() }
    }

    fun demo(): List<MediaItem> = listOf(
        MediaItem(
            url = "https://example.com/media/master.m3u8",
            pageUrl = "https://example.com/watch/1024",
            title = "示例 · HLS 流",
            type = MediaType.HLS
        ),
        MediaItem(
            url = "https://example.com/media/vidma_20260723_014032.mp4",
            pageUrl = "https://example.com/watch/1024",
            title = "vidma_20260723_014032.mp4",
            type = MediaType.MP4,
            contentLength = 48_372_941
        )
    )
}
