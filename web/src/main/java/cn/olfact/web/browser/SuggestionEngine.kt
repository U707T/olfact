package cn.olfact.web.browser

import java.net.URLEncoder
import java.util.Locale

/** 地址栏下拉项。 */
data class UrlBarSuggestion(
    val text: String,
    val url: String,
    val kind: Kind
) {
    enum class Kind { INPUT_URL, HISTORY, TEXT_LINK, SEARCH }
}

/**
 * 地址栏输入判定 + 候选生成。纯逻辑，便于单测。
 */
object SuggestionEngine {

    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}(:\d{1,5})?$""")
    private val HOST_WITH_TLD = Regex("""^[a-zA-Z0-9][a-zA-Z0-9.-]*\.[a-zA-Z]{2,24}(:\d{1,5})?(/.*)?$""")
    private val LOCALHOST = Regex("""^localhost(:\d{1,5})?(/.*)?$""", RegexOption.IGNORE_CASE)

    fun isUrlLike(input: String): Boolean {
        val text = input.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() }) return false
        if (text.contains("://")) return true
        if (LOCALHOST.matches(text)) return true
        if (IPV4.matches(text)) return true
        if (text.startsWith("about:") || text.startsWith("file:")) return true
        return HOST_WITH_TLD.matches(text)
    }

    fun toUrl(input: String, searchTemplate: String): String {
        val text = input.trim()
        if (text.isEmpty()) return ""
        if (text.contains("://")) return text
        if (LOCALHOST.matches(text) || IPV4.matches(text)) return "http://$text"
        if (HOST_WITH_TLD.matches(text)) return "https://$text"
        return searchTemplate.replace("%s", URLEncoder.encode(text, "UTF-8"))
    }

    fun suggestions(
        input: String,
        history: List<String> = emptyList(),
        searchTemplate: String = "https://www.bing.com/search?q=%s",
        limit: Int = 8
    ): List<UrlBarSuggestion> {
        val text = input.trim()
        if (text.isEmpty()) return emptyList()
        val out = LinkedHashMap<String, UrlBarSuggestion>()

        if (isUrlLike(text)) {
            val url = toUrl(text, searchTemplate)
            out[url.lowercase(Locale.ROOT)] = UrlBarSuggestion(text, url, UrlBarSuggestion.Kind.INPUT_URL)
        }

        history.asSequence()
            .filter { it.contains(text, ignoreCase = true) }
            .take(3)
            .forEach { url ->
                out.putIfAbsent(url.lowercase(Locale.ROOT), UrlBarSuggestion(url, url, UrlBarSuggestion.Kind.HISTORY))
            }

        LinkExtractor.extract(text, LinkExtractor.MAX_CANDIDATES).forEach { candidate ->
            out.putIfAbsent(
                candidate.url.lowercase(Locale.ROOT),
                UrlBarSuggestion(candidate.raw, candidate.url, UrlBarSuggestion.Kind.TEXT_LINK)
            )
        }

        val searchUrl = searchTemplate.replace("%s", URLEncoder.encode(text, "UTF-8"))
        out.putIfAbsent(
            searchUrl.lowercase(Locale.ROOT),
            UrlBarSuggestion(text, searchUrl, UrlBarSuggestion.Kind.SEARCH)
        )

        return out.values.take(limit)
    }
}
