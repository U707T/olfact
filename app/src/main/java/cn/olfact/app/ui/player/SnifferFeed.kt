package cn.olfact.app.ui.player

import cn.olfact.core.api.MediaItem
import cn.olfact.core.api.MediaType

/**
 * 嗅探结果缓存。真实实现由 B2 在 `shouldInterceptRequest` 里调 `MediaSniffer.onMediaDetected`，
 * 这里保存成列表供播放页 / 嗅探弹层读取。
 */
object SnifferFeed {

    val items = mutableListOf<MediaItem>()

    fun add(item: MediaItem) {
        if (items.any { it.url == item.url }) return
        items.add(0, item)
    }

    fun forPage(pageUrl: String): List<MediaItem> =
        items.filter { pageUrl.isBlank() || it.pageUrl == pageUrl }

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
