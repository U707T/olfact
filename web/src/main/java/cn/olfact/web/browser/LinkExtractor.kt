package cn.olfact.web.browser

import java.util.Locale

/** 从一段文本里提取出的候选链接。 */
data class LinkCandidate(val raw: String, val url: String, val kind: Kind) {
    enum class Kind { HTTP, WWW, BARE_DOMAIN }
}

/**
 * 文本提链：地址栏粘贴一段话时，把里面的网址挑出来（最多 [MAX_CANDIDATES] 条）。
 *
 * 纯函数，无 Android 依赖，可直接跑单元测试。
 */
object LinkExtractor {

    const val MAX_CANDIDATES = 5

    private const val TAIL_CHARS = ".,;:!?'\"”’）)]}】>》》、。"

    private val HTTP = Regex(
        """https?://[^\s"'<>()\[\]{}，。；：！？、（）《》【】]+""",
        RegexOption.IGNORE_CASE
    )

    private val WWW = Regex(
        """www\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+[^\s"'<>()\[\]{}，。；：！？、（）《》【】]*""",
        RegexOption.IGNORE_CASE
    )

    private val BARE_DOMAIN = Regex(
        """(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,24}(?::\d{1,5})?(?:/[^\s"'<>()\[\]{}，。；：！？、（）《》【】]*)?"""
    )

    fun extract(text: String, limit: Int = MAX_CANDIDATES): List<LinkCandidate> {
        if (text.isBlank() || limit <= 0) return emptyList()
        val taken = ArrayList<IntRange>()
        val result = LinkedHashMap<String, LinkCandidate>()

        fun collect(regex: Regex, kind: LinkCandidate.Kind, prefix: String) {
            for (match in regex.findAll(text)) {
                if (result.size >= limit) return
                val range = match.range
                if (taken.any { it.first <= range.last && range.first <= it.last }) continue
                if (range.first > 0 && text[range.first - 1] == '@') continue   // 邮箱里的域名不算
                val raw = trimTail(match.value)
                if (raw.length < 4) continue
                val url = when {
                    raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
                    else -> prefix + raw
                }
                val key = url.lowercase(Locale.ROOT).trimEnd('/')
                if (result.containsKey(key)) continue
                taken += range
                result[key] = LinkCandidate(raw = raw, url = url, kind = kind)
            }
        }

        collect(HTTP, LinkCandidate.Kind.HTTP, "")
        collect(WWW, LinkCandidate.Kind.WWW, "http://")
        collect(BARE_DOMAIN, LinkCandidate.Kind.BARE_DOMAIN, "http://")

        return result.values.take(limit)
    }

    /** 判断整段输入是否本身就只是一个链接（用于地址栏判断走 URL 还是搜索）。 */
    fun singleUrl(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        val candidates = extract(trimmed, 1)
        val only = candidates.firstOrNull() ?: return null
        val sameRaw = only.raw.equals(trimmed, ignoreCase = true)
        val sameUrl = only.url.equals(trimmed, ignoreCase = true)
        if (!sameRaw && !sameUrl) return null
        // 裸域名/ www 开头：地址栏默认走 https，比 http 更符合预期
        return if ((only.kind == LinkCandidate.Kind.BARE_DOMAIN || only.kind == LinkCandidate.Kind.WWW) &&
            only.url.startsWith("http://")
        ) {
            "https://" + only.url.removePrefix("http://")
        } else {
            only.url
        }
    }

    private fun trimTail(value: String): String {
        var end = value.length
        while (end > 0 && TAIL_CHARS.indexOf(value[end - 1]) >= 0) end--
        return value.substring(0, end)
    }
}
