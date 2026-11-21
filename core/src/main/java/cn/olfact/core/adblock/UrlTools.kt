package cn.olfact.core.adblock

/**
 * 纯字符串 URL 工具：不依赖 `android.net.Uri`，方便在 JVM 单测里直接跑。
 * 所有输入按小写处理（调用方负责在需要 match-case 时保留原文）。
 */
object UrlTools {

    /** 常见二级后缀，用于把 `a.b.com.cn` 归到 `b.com.cn`。不做完整 PSL，够拦截用。 */
    private val MULTI_PART_SUFFIX = setOf(
        "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn", "ac.cn",
        "com.hk", "com.tw", "com.sg", "com.au", "com.br", "com.mx", "com.tr",
        "co.uk", "org.uk", "ac.uk", "co.jp", "co.kr", "co.in", "co.nz", "co.za"
    )

    fun stripFragment(url: String): String {
        val i = url.indexOf('#')
        return if (i >= 0) url.substring(0, i) else url
    }

    /** 取 authority 里的 host（去掉 userinfo / 端口 / IPv6 方括号外的东西），失败返回空串。 */
    fun hostOf(url: String): String {
        val s = url.trim()
        if (s.isEmpty()) return ""
        val schemeEnd = s.indexOf("://")
        val start = if (schemeEnd >= 0) schemeEnd + 3 else 0
        var end = s.length
        for (i in start until s.length) {
            val c = s[i]
            if (c == '/' || c == '?' || c == '#') {
                end = i
                break
            }
        }
        if (end <= start) return ""
        var authority = s.substring(start, end)
        val at = authority.lastIndexOf('@')
        if (at >= 0) authority = authority.substring(at + 1)
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            return if (close > 0) authority.substring(0, close + 1).lowercase() else authority.lowercase()
        }
        val colon = authority.indexOf(':')
        if (colon >= 0) authority = authority.substring(0, colon)
        return authority.lowercase().trimEnd('.')
    }

    fun isIp(host: String): Boolean {
        if (host.startsWith("[")) return true
        if (host.isEmpty()) return false
        val parts = host.split('.')
        if (parts.size != 4) return false
        return parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } && (p.toIntOrNull() ?: 256) <= 255 }
    }

    /** 注册域（近似）：`ads.example.com` → `example.com`。用于判断第三方。 */
    fun siteKey(host: String): String {
        val h = host.trim().trimEnd('.').lowercase()
        if (h.isEmpty() || isIp(h)) return h
        val labels = h.split('.')
        if (labels.size <= 2) return h
        val last2 = labels.takeLast(2).joinToString(".")
        return if (last2 in MULTI_PART_SUFFIX) labels.takeLast(3).joinToString(".") else last2
    }

    fun isThirdParty(requestHost: String, pageHost: String): Boolean {
        if (requestHost.isEmpty() || pageHost.isEmpty()) return false
        return siteKey(requestHost) != siteKey(pageHost)
    }

    /** `host == domain` 或 `host` 是 `domain` 的子域。 */
    fun hostMatches(host: String, domain: String): Boolean {
        if (host.isEmpty() || domain.isEmpty()) return false
        val d = domain.trim().removePrefix("*.").trimStart('.')
        return host == d || host.endsWith(".$d")
    }

    /** `a.b.example.com` → [a.b.example.com, b.example.com, example.com, com] */
    fun suffixesOf(host: String): List<String> {
        if (host.isEmpty()) return emptyList()
        val labels = host.split('.')
        val out = ArrayList<String>(labels.size)
        for (i in labels.indices) {
            out.add(labels.subList(i, labels.size).joinToString("."))
        }
        return out
    }

    /** Index every three-character ASCII window; rules may match inside a URL segment. */
    fun tokensOf(url: String): Set<String> {
        val out = HashSet<String>(32)
        var run = 0
        for (i in url.indices) {
            val c = url[i]
            run = if (c in 'a'..'z' || c in '0'..'9') run + 1 else 0
            if (run >= 3) out.add(url.substring(i - 2, i + 1))
        }
        return out
    }

    /** Raw regex literals are not necessarily required (alternation, optional groups, etc.). */
    fun bucketTokenOf(pattern: String): String? {
        if (pattern.length > 2 && pattern.startsWith('/') && pattern.endsWith('/')) return null
        var start = -1
        var end = 0
        var run = 0
        for (i in pattern.indices) {
            val c = pattern[i]
            run = if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9') run + 1 else 0
            if (run > end - start && run >= 3) {
                start = i - run + 1
                end = i + 1
            }
        }
        return if (start >= 0) pattern.substring(start, start + 3).lowercase() else null
    }
}
