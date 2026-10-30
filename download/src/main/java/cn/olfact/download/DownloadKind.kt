package cn.olfact.download

/** 下载源类型，决定走哪个引擎。 */
enum class DownloadKind(val wireName: String) {
    /** 普通单文件：mp4/webm/mp3… */
    PROGRESSIVE("progressive"),

    /** HLS：m3u8 + 分片 + 合并 */
    HLS("hls"),

    /** DASH：目前只做整段 mpd 提示，不解析（方案里没有 DASH 下载需求） */
    DASH("dash"),

    UNKNOWN("unknown");

    companion object {
        fun fromWire(name: String?): DownloadKind =
            entries.firstOrNull { it.wireName.equals(name, ignoreCase = true) } ?: UNKNOWN

        fun detect(url: String?, mime: String? = null): DownloadKind {
            val path = url?.substringBefore('?')?.substringBefore('#')?.lowercase().orEmpty()
            if (path.endsWith(".m3u8") || path.endsWith(".m3u")) return HLS
            if (path.endsWith(".mpd")) return DASH
            val m = mime?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            if (m.contains("mpegurl")) return HLS
            if (m.contains("dash+xml")) return DASH
            return if (path.isNotEmpty()) PROGRESSIVE else UNKNOWN
        }
    }
}
