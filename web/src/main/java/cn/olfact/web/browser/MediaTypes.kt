package cn.olfact.web.browser

import cn.olfact.core.api.MediaType

/**
 * 从 URL 判断媒体类型。只看路径扩展名，不做内容嗅探（内容由播放器兜底）。
 */
object MediaTypes {

    fun of(url: String): MediaType? {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".m3u8") -> MediaType.HLS
            path.endsWith(".mpd") -> MediaType.DASH
            path.endsWith(".mp4") || path.endsWith(".m4v") -> MediaType.MP4
            path.endsWith(".webm") || path.endsWith(".mkv") || path.endsWith(".flv") -> MediaType.OTHER
            else -> null
        }
    }

    fun isMedia(url: String): Boolean = of(url) != null
}
