package cn.olfact.web.browser

import cn.olfact.core.api.MediaType

/**
 * 从 URL 判断媒体类型。只看路径扩展名，不做内容嗅探（内容由播放器兜底）。
 */
object MediaTypes {

    fun of(url: String): MediaType? {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals("http", true) && !uri.scheme.equals("https", true)) return null
        if (uri.rawAuthority.isNullOrEmpty()) return null
        val path = uri.path.orEmpty().lowercase()
        return when (path.substringAfterLast('/').substringAfterLast('.', "")) {
            "m3u8" -> MediaType.HLS
            "mpd" -> MediaType.DASH
            "mp4", "m4v" -> MediaType.MP4
            "webm", "mkv", "flv", "mov" -> MediaType.OTHER
            "m4a", "mp3", "aac", "ogg", "opus", "flac", "wav" -> MediaType.AUDIO
            else -> null
        }
    }

    /** Preserve request context, not Chromium's byte range, validators, or transport headers. */
    internal fun replayHeaders(request: Map<String, String>, userAgent: String?): Map<String, String> = buildMap {
        for (name in listOf("Referer", "User-Agent", "Cookie", "Authorization", "Origin")) {
            val value = request.entries.firstOrNull { it.key.equals(name, true) }?.value
                ?: userAgent.takeIf { name == "User-Agent" }
            if (!value.isNullOrBlank()) put(name, value)
        }
    }

    fun isMedia(url: String): Boolean = of(url) != null
}
