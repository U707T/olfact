package cn.olfact.core.api

/** 媒体类型，嗅探层只负责识别，怎么播由 player 决定。 */
enum class MediaType {
    HLS, DASH, MP4, OTHER
}

/**
 * 一条被嗅探到的媒体资源。
 *
 * @param url 媒体直链（可能是相对地址，嗅探层会补全成绝对地址）
 * @param type 媒体类型
 * @param pageUrl 发现它的页面地址
 * @param pageTitle 页面标题（供播放器/下载列表展示）
 * @param headers 复用页面请求所需的头（Referer / User-Agent / Cookie）
 * @param sizeBytes 已知大小，未知为 -1
 * @param durationMs 已知时长，未知为 -1
 */
data class MediaItem(
    val url: String,
    val type: MediaType,
    val pageUrl: String = "",
    val pageTitle: String = "",
    val headers: Map<String, String> = emptyMap(),
    val sizeBytes: Long = -1L,
    val durationMs: Long = -1L
)

/**
 * 由 web 模块在 shouldInterceptRequest 阶段回调，player/download 侧消费。
 * 实现方（B3）必须保证回调是线程安全的，可能来自 WebView 的 IO 线程。
 */
interface MediaSniffer {
    fun onMediaDetected(item: MediaItem)
    fun currentPageTitle(): String
    fun currentPageUrl(): String
}
