package cn.olfact.download.m3u8

data class AdFilterConfig(
    val enabled: Boolean = true,
    /** 丢弃 EXT-X-CUE-OUT / DATERANGE 标记出的广告段。 */
    val dropCueOut: Boolean = true,
    /** 按 URL/标题里的广告标记丢弃。 */
    val dropUrlMarkers: Boolean = true,
    val urlMarkers: List<String> = DEFAULT_MARKERS,
) {
    companion object {
        val DEFAULT_MARKERS = listOf(
            "/ad/", "/ads/", "advert", "_ad_", "-ad-", "/preroll", "/midroll", "/postroll",
            "doubleclick", "imasdk", "googlesyndication", "vast.xml",
        )
    }
}

data class AdFilterResult(
    val segments: List<M3u8Segment>,
    val dropped: Int,
    /** 全部被过滤时回退为原列表，避免"整片被删"这种灾难性结果。 */
    val fallbackToAll: Boolean = false,
)

/**
 * 广告分片过滤。只做"去掉明显是广告的段"，不做智能识别：
 * - 规则 1：解析阶段标记的 CUE-OUT / DATERANGE 区间；
 * - 规则 2：URL 或 EXTINF 标题里的广告关键词；
 * - 兜底：若过滤后一个不剩，退回完整列表（宁可带广告也不能播不了）。
 */
object AdBreakFilter {

    fun filter(segments: List<M3u8Segment>, config: AdFilterConfig = AdFilterConfig()): AdFilterResult {
        if (!config.enabled || segments.isEmpty()) return AdFilterResult(segments, 0)

        val kept = ArrayList<M3u8Segment>(segments.size)
        var dropped = 0
        var pendingDiscontinuity = false

        for (segment in segments) {
            val isAd = (config.dropCueOut && segment.adBreak) ||
                (config.dropUrlMarkers && matchesMarker(segment, config.urlMarkers))
            if (isAd) {
                dropped++
                pendingDiscontinuity = pendingDiscontinuity || segment.discontinuity || segment.adBreak
                continue
            }
            // 丢弃发生在中间：拼接点必须标 discontinuous，否则 remux 后时间戳会跳。
            kept += if (pendingDiscontinuity && !segment.discontinuity) {
                segment.copy(discontinuity = true)
            } else {
                segment
            }
            pendingDiscontinuity = false
        }

        if (kept.isEmpty()) return AdFilterResult(segments, 0, fallbackToAll = true)
        return AdFilterResult(kept, dropped)
    }

    private fun matchesMarker(segment: M3u8Segment, markers: List<String>): Boolean {
        val haystack = (segment.uri + " " + (segment.title ?: "")).lowercase()
        return markers.any { haystack.contains(it) }
    }
}
