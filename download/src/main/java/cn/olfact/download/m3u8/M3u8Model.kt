package cn.olfact.download.m3u8

/** EXT-X-KEY。method=NONE 时 [uri] 为空。 */
data class M3u8Key(
    val method: String,
    val uri: String? = null,
    val ivHex: String? = null,
    val keyFormat: String? = null,
) {
    val isEncrypted: Boolean get() = !method.equals("NONE", ignoreCase = true)
    val isAes128: Boolean get() = method.equals("AES-128", ignoreCase = true)
}

data class M3u8Segment(
    val uri: String,
    val durationMs: Long,
    val sequence: Long,
    val discontinuity: Boolean = false,
    val key: M3u8Key? = null,
    val mapUri: String? = null,
    val byteRangeLength: Long? = null,
    val byteRangeOffset: Long? = null,
    val title: String? = null,
    /** 由 EXT-X-CUE-OUT / EXT-X-DATERANGE 判定出的广告区间。 */
    val adBreak: Boolean = false,
)

data class M3u8Variant(
    val uri: String,
    val bandwidth: Long = 0L,
    val resolution: String? = null,
    val codecs: String? = null,
    val frameRate: Double? = null,
) {
    val height: Int
        get() = resolution?.substringAfter('x')?.toIntOrNull() ?: 0

    val width: Int
        get() = resolution?.substringBefore('x')?.toIntOrNull() ?: 0
}

data class M3u8Playlist(
    val url: String,
    val isMaster: Boolean,
    val variants: List<M3u8Variant> = emptyList(),
    val segments: List<M3u8Segment> = emptyList(),
    val targetDurationSec: Int = 0,
    val mediaSequence: Long = 0L,
    val ended: Boolean = false,
    val totalDurationMs: Long = 0L,
    val hasEncryption: Boolean = false,
) {
    val segmentCount: Int get() = segments.size

    /** 选最高码率（同码率取分辨率高的）。 */
    fun bestVariant(): M3u8Variant? = variants.maxWithOrNull(
        compareBy<M3u8Variant> { it.bandwidth }.thenBy { it.height }
    )

    /** 按网络情况选一个不超过 [maxHeight] 的最高档。 */
    fun variantFor(maxHeight: Int): M3u8Variant? {
        if (variants.isEmpty()) return null
        val capped = variants.filter { it.height == 0 || it.height <= maxHeight }
        return (capped.ifEmpty { variants }).maxByOrNull { it.bandwidth }
    }
}
