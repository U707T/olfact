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

    /**
     * URL 的匹配 token：按非字母数字切开，每个片段产出「整段 + 前 3 字符 + 前 4 字符」。
     *
     * 为什么带前缀：规则侧用 pattern 里最长片段的**前 4 个字符**做倒排 key（见 [bucketTokenOf]），
     * 而 URL 里的片段可能更长（`banner42` vs 规则里的 `banner`），只有加前缀才能命中。
     */
    fun tokensOf(url: String): Set<String> {
        val out = HashSet<String>(32)
        var start = -1
        for (i in url.indices) {
            val c = url[i]
            val alnum = (c in 'a'..'z') || (c in '0'..'9')
            if (alnum) {
                if (start < 0) start = i
            } else if (start >= 0) {
                addToken(out, url, start, i)
                start = -1
            }
        }
        if (start >= 0) addToken(out, url, start, url.length)
        return out
    }

    /**
     * 从 ABP pattern 里抽倒排索引 key：
     * 取最长字母数字片段，长度 ≥4 用前 4 字符，恰为 3 用整段；否则返回 null（进 generic 桶）。
     */
    fun bucketTokenOf(pattern: String): String? {
        var best = ""
        var start = -1
        fun flush(end: Int) {
            if (start >= 0 && end - start >= 3 && end - start > best.length) {
                best = pattern.substring(start, end).lowercase()
            }
            start = -1
        }
        for (i in pattern.indices) {
            val c = pattern[i]
            val alnum = (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9')
            if (alnum) {
                if (start < 0) start = i
            } else {
                flush(i)
            }
        }
        flush(pattern.length)
        return when {
            best.length >= 4 -> best.substring(0, 4)
            best.length == 3 -> best
            else -> null
        }
    }

    private fun addToken(out: MutableSet<String>, s: String, from: Int, to: Int) {
        if (to - from < 3) return
        out.add(s.substring(from, to))
        out.add(s.substring(from, from + 3))
        if (to - from > 4) out.add(s.substring(from, from + 4))
    }
}
