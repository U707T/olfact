package cn.olfact.download

/** 半开区间 [start, endInclusive]，长度 = endInclusive - start + 1。 */
data class ByteRange(val index: Int, val start: Long, val endInclusive: Long) {
    val length: Long get() = endInclusive - start + 1
    fun header(): String = "bytes=$start-$endInclusive"
}

object ChunkPlanner {

    const val DEFAULT_CHUNK_SIZE = 4L * 1024 * 1024
    private const val MAX_CHUNKS = 4000

    /**
     * 按 [chunkSize] 切片，块数过多时自动放大块大小（避免 10GB 文件切出上万个 part）。
     * 返回的区间必须严丝合缝覆盖 [0, totalBytes)，这是合并正确性的前提。
     */
    fun plan(totalBytes: Long, chunkSize: Long = DEFAULT_CHUNK_SIZE): List<ByteRange> {
        if (totalBytes <= 0L) return emptyList()
        var size = if (chunkSize <= 0L) DEFAULT_CHUNK_SIZE else chunkSize
        if (totalBytes / size > MAX_CHUNKS) {
            size = (totalBytes + MAX_CHUNKS - 1) / MAX_CHUNKS
        }
        val result = ArrayList<ByteRange>((totalBytes / size + 1).toInt())
        var start = 0L
        var index = 0
        while (start < totalBytes) {
            val end = minOf(start + size - 1, totalBytes - 1)
            result += ByteRange(index, start, end)
            start = end + 1
            index++
        }
        return result
    }

    /** 用已有 part 文件大小恢复进度。 */
    fun resumeOffset(range: ByteRange, partLength: Long): Long =
        range.start + partLength.coerceIn(0L, range.length)
}
