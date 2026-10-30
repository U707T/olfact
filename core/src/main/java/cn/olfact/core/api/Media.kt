package cn.olfact.core.api

/** 嗅探到的媒体类型。 */
enum class MediaKind { HLS, MP4, DASH, AUDIO, UNKNOWN }

/**
 * 一条嗅探结果。[url] 为可直接播放的媒体地址，[pageUrl] 为来源页面。
 */
data class MediaItem(
    val url: String,
    val pageUrl: String = "",
    val title: String = "",
    val kind: MediaKind = MediaKind.UNKNOWN,
    val sizeBytes: Long = 0L,
    val detectedAt: Long = System.currentTimeMillis()
)

/**
 * 嗅探回调。B2 在 `shouldInterceptRequest` 命中媒体时调用 [onMediaDetected]，
 * 页面侧通过 [currentPageTitle] / [currentPageUrl] 补齐上下文。
 */
interface MediaSniffer {
    fun onMediaDetected(item: MediaItem)
    fun currentPageTitle(): String
    fun currentPageUrl(): String
}
