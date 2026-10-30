package cn.olfact.player

/**
 * 播放器唯一入参。刻意不依赖 `core/api` 或 `web` 的实现：
 * 上层（B5 的界面 / B2 的嗅探列表）只需把 `MediaItem` 映射成这个 data class。
 *
 * 与 core 的适配（等 B1 的 `core/api/MediaItem` 落地后由挂载方写 10 行）：
 * ```
 * fun coreMediaItem.toPlaybackSource() = PlaybackSource(
 *     url = url, kind = MediaKind.fromWire(kind), title = title,
 *     pageUrl = pageUrl, headers = headers, userAgent = userAgent,
 *     referer = referer, live = isLive,
 * )
 * ```
 */
data class PlaybackSource(
    val url: String,
    val kind: MediaKind = MediaKind.detect(url),
    val title: String? = null,
    val pageUrl: String? = null,
    val pageTitle: String? = null,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val userAgent: String? = null,
    val referer: String? = null,
    val durationHintMs: Long = TIME_UNSET,
    val live: Boolean = false,
    /** 无痕模式下不写播放进度。 */
    val isPrivate: Boolean = false,
) {
    /** 断点续播的存储 key：同一 URL 的不同清晰度要区分开。 */
    val resumeKey: String
        get() = buildString {
            append(url)
            if (isPrivate) append("#private")
            val extra = listOfNotNull(referer, pageUrl).joinToString("|")
            if (extra.isNotEmpty()) append('#').append(extra.hashCode().toUInt().toString(16))
        }

    fun resolvedMimeType(): String? = mimeType ?: kind.mimeType

    companion object {
        const val TIME_UNSET: Long = -1L
    }
}
