package cn.olfact.ai.summarize

data class Article(
    val title: String,
    val text: String,
    val siteName: String? = null,
    val url: String? = null,
    val charCount: Int = text.length,
    val paragraphCount: Int = 0,
)

/**
 * 轻量 Readability：不引 jsoup，用正则做"去噪 + 段落打分"。
 * 目标不是 100% 准确，而是把正文捞出来喂给模型；抽不到就退回全文。
 */
object ReadabilityExtractor {

    private val scriptRegex = Regex("""<script\b[^>]*>.*?</script>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val styleRegex = Regex("""<style\b[^>]*>.*?</style>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val noisyBlocks = Regex(
        """<(nav|header|footer|aside|form|noscript|template|svg|iframe|canvas)\b[^>]*>.*?</\1>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val commentRegex = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
    private val titleTagRegex = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val h1Regex = Regex("""<h1[^>]*>(.*?)</h1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val ogTitleRegex = Regex("""<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val ogSiteRegex = Regex("""<meta[^>]+property=["']og:site_name["'][^>]+content=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val paragraphRegex = Regex("""<p\b[^>]*>(.*?)</p>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val articleRegex = Regex("""<article\b[^>]*>(.*?)</article>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val tagRegex = Regex("""<[^>]+>""")
    private val whitespaceRegex = Regex("""[ \t\u00A0]+""")
    private val blankLinesRegex = Regex("""\n{3,}""")

    fun extract(html: String, url: String? = null): Article {
        val cleaned = html
            .replace(commentRegex, " ")
            .replace(scriptRegex, " ")
            .replace(styleRegex, " ")
            .replace(noisyBlocks, " ")

        val title = firstNonBlank(
            ogTitleRegex.find(html)?.groupValues?.getOrNull(1),
            titleTagRegex.find(html)?.groupValues?.getOrNull(1),
            h1Regex.find(cleaned)?.groupValues?.getOrNull(1),
        ).orEmpty().let(::plainText).trim()

        val siteName = ogSiteRegex.find(html)?.groupValues?.getOrNull(1)?.let(::plainText)?.trim()

        val paragraphs = buildList {
            paragraphRegex.findAll(cleaned).forEach { add(plainText(it.groupValues[1])) }
            if (size < MIN_PARAGRAPHS) {
                articleRegex.findAll(cleaned).forEach { match ->
                    match.groupValues[1].split("\n").forEach { add(plainText(it)) }
                }
            }
        }.map { it.trim() }
            .filter { it.length >= MIN_PARAGRAPH_CHARS }
            .distinct()

        val selected = if (paragraphs.isNotEmpty()) assemble(paragraphs) else plainText(cleaned)

        return Article(
            title = title.ifBlank { url?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "网页" },
            text = selected.trim(),
            siteName = siteName,
            url = url,
            charCount = selected.length,
            paragraphCount = paragraphs.size,
        )
    }

    /** 按长度 + 标点密度打分，取到字符预算为止。 */
    private fun assemble(paragraphs: List<String>): String {
        val scored = paragraphs.map { text ->
            val score = text.length + punctuationCount(text) * 12
            text to score
        }.sortedByDescending { it.second }

        val builder = StringBuilder()
        for ((text, _) in scored) {
            if (builder.length + text.length > MAX_CHARS) continue
            if (builder.isNotEmpty()) builder.append("\n\n")
            builder.append(text)
        }
        return if (builder.isEmpty()) paragraphs.joinToString("\n\n") else builder.toString()
    }

    private fun punctuationCount(text: String): Int =
        text.count { it == '，' || it == '。' || it == ',' || it == '.' || it == '！' || it == '?' }

    fun plainText(html: String): String = decodeEntities(html.replace(tagRegex, " "))
        .replace(whitespaceRegex, " ")
        .replace("\r", "")
        .replace(blankLinesRegex, "\n\n")
        .trim()

    fun decodeEntities(input: String): String {
        if (!input.contains('&')) return input
        var text = input
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&hellip;", "…")
        text = NUMERIC_ENTITY.replace(text) { match ->
            val code = match.groupValues[1].toIntOrNull() ?: return@replace match.value
            runCatching { String(Character.toChars(code)) }.getOrDefault(match.value)
        }
        return text
    }

    private fun firstNonBlank(vararg candidates: String?): String? =
        candidates.firstOrNull { !it.isNullOrBlank() }

    private val NUMERIC_ENTITY = Regex("""&#(\d+);""")

    private const val MIN_PARAGRAPHS = 2
    private const val MIN_PARAGRAPH_CHARS = 20
    private const val MAX_CHARS = 48_000
}
