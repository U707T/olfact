package cn.olfact.player

import androidx.media3.common.MimeTypes

/**
 * 播放源类型。嗅探层 ([cn.olfact.core.api.MediaSniffer]) 给出 URL/MIME，
 * 这里只负责把它翻译成 Media3 需要的 MIME。
 */
enum class MediaKind(val wireName: String, val mimeType: String?) {
    HLS("hls", MimeTypes.APPLICATION_M3U8),
    DASH("dash", MimeTypes.APPLICATION_MPD),
    PROGRESSIVE("progressive", null),
    UNKNOWN("unknown", null);

    companion object {
        fun fromWire(name: String?): MediaKind =
            entries.firstOrNull { it.wireName.equals(name, ignoreCase = true) } ?: UNKNOWN

        /**
         * 先看 URL 扩展名，再看 MIME。
         * 顺序很重要：CDN 常把 m3u8 回成 application/octet-stream，
         * 若先信 MIME 会被当成普通文件下载/播放失败。
         */
        fun detect(url: String?, mimeHint: String? = null): MediaKind {
            val path = url?.substringBefore('?')?.substringBefore('#')?.lowercase().orEmpty()
            when {
                path.endsWith(".m3u8") || path.endsWith(".m3u") -> return HLS
                path.endsWith(".mpd") -> return DASH
                path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".webm") ||
                    path.endsWith(".mkv") || path.endsWith(".mov") || path.endsWith(".ts") ||
                    path.endsWith(".flv") || path.endsWith(".3gp") || path.endsWith(".m4a") ||
                    path.endsWith(".mp3") || path.endsWith(".aac") -> return PROGRESSIVE
            }
            val mime = mimeHint?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            val hlsMimes = setOf(
                MimeTypes.APPLICATION_M3U8.lowercase(),
                "application/vnd.apple.mpegurl",
                "audio/mpegurl",
                "audio/x-mpegurl",
            )
            val dashMimes = setOf(MimeTypes.APPLICATION_MPD.lowercase(), "application/dash+xml")
            return when {
                mime in hlsMimes -> HLS
                mime in dashMimes -> DASH
                mime.isEmpty() -> UNKNOWN
                mime.startsWith("video/") || mime.startsWith("audio/") ||
                    mime == "application/octet-stream" -> PROGRESSIVE
                else -> UNKNOWN
            }
        }

        /** 交给外部播放器时用的 MIME（尽量给一个具体值，避免 chooser 过滤掉播放器）。 */
        fun externalMime(kind: MediaKind, declared: String?): String = when {
            !declared.isNullOrBlank() -> declared
            kind == HLS -> "application/x-mpegurl"
            kind == DASH -> "application/dash+xml"
            else -> "video/*"
        }
    }
}
