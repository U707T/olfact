package cn.olfact.core.api

import kotlinx.serialization.Serializable

/**
 * 嗅探到的媒体资源。B2 产出（shouldInterceptRequest 命中），B3 消费（Media3 起播 / 下载）。
 *
 * 契约文件：只有 B1 能改字段；需要额外信息请塞进 [extras]。
 */
@Serializable
data class MediaItem(
    /** 媒体直链（m3u8 / mpd / mp4 / m4a…） */
    val url: String,
    val type: MediaType = MediaType.OTHER,
    /** 媒体标题，通常取页面标题或 URL 末段 */
    val title: String? = null,
    /** 发现该媒体的页面地址（下载时作为 Referer 来源） */
    val pageUrl: String? = null,
    val pageTitle: String? = null,
    val mimeType: String? = null,
    /** Content-Length，未知为 null */
    val contentLength: Long? = null,
    /** 时长（毫秒），仅 m3u8 的 EXTINF 汇总或响应头可得时才填 */
    val durationMs: Long? = null,
    /** 播放/下载需要透传的请求头：UA、Referer、Cookie、Authorization… */
    val headers: Map<String, String> = emptyMap(),
    /** AES-128 等加密 HLS，播放器需带 key 处理 */
    val isEncrypted: Boolean = false,
    /** 各批次自定义字段，避免为小事改契约 */
    val extras: Map<String, String> = emptyMap()
)

@Serializable
enum class MediaType {
    /** HLS：.m3u8 */
    HLS,

    /** DASH：.mpd */
    DASH,

    /** 渐进式视频：.mp4 / .webm / .flv */
    MP4,

    /** 纯音频：.m4a / .mp3 / .aac */
    AUDIO,

    OTHER;

    companion object {
        /** 按 URL 路径猜类型，B2 在 shouldInterceptRequest 里直接用。 */
        fun fromUrl(url: String): MediaType {
            val path = url.substringBefore('?').substringBefore('#').lowercase()
            return when {
                path.endsWith(".m3u8") -> HLS
                path.endsWith(".mpd") -> DASH
                path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".flv") ||
                    path.endsWith(".mkv") -> MP4
                path.endsWith(".m4a") || path.endsWith(".mp3") || path.endsWith(".aac") ||
                    path.endsWith(".ogg") -> AUDIO
                else -> OTHER
            }
        }
    }
}

/**
 * 嗅探回调。实现方通常是界面层（B3 的嗅探列表 / B5 的容器），
 * B2 在 `shouldInterceptRequest` 里调用。
 */
interface MediaSniffer {
    fun onMediaDetected(item: MediaItem)

    fun currentPageTitle(): String

    fun currentPageUrl(): String
}
