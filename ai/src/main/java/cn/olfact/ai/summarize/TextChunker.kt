package cn.olfact.ai.summarize

/**
 * 按段落/句子切块，保证：
 * 1. 每块不超过 maxChars；
 * 2. 块之间有余量重叠，跨块的句子不会被摘要丢掉；
 * 3. 不丢字符（除重叠导致的重复）。
 */
object TextChunker {

    fun chunk(
        text: String,
        maxChars: Int = 6000,
        overlapChars: Int = 120,
    ): List<String> {
        require(maxChars > 0) { "maxChars 必须大于 0" }
        val normalized = text.replace("\r\n", "\n").trim()
        if (normalized.isEmpty()) return emptyList()
        if (normalized.length <= maxChars) return listOf(normalized)

        val paragraphs = normalized.split(Regex("""\n{2,}"""))
        val chunks = ArrayList<String>()
        val current = StringBuilder()

        fun flush() {
            if (current.isEmpty()) return
            chunks += current.toString().trim()
            current.setLength(0)
        }

        for (paragraph in paragraphs) {
            if (paragraph.length > maxChars) {
                flush()
                chunks += hardSplit(paragraph, maxChars)
                continue
            }
            if (current.length + paragraph.length + 2 > maxChars) {
                val tail = current.toString().takeLast(overlapChars.coerceAtMost(current.length))
                flush()
                if (tail.isNotBlank()) current.append(tail)
            }
            if (current.isNotEmpty()) current.append("\n\n")
            current.append(paragraph)
        }
        flush()
        return chunks.filter { it.isNotBlank() }
    }

    private fun hardSplit(text: String, maxChars: Int): List<String> {
        val pieces = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            val end = minOf(start + maxChars, text.length)
            // 尽量在句号处断开
            var cut = end
            if (end < text.length) {
                val windowStart = maxOf(start, end - 200)
                val sentenceEnd = text.lastIndexOfAny(charArrayOf('。', '！', '？', '.', '!', '?', '\n'), end)
                if (sentenceEnd in windowStart until end) cut = sentenceEnd + 1
            }
            pieces += text.substring(start, cut).trim()
            start = cut
        }
        return pieces.filter { it.isNotBlank() }
    }
}
