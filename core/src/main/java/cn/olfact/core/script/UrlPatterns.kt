package cn.olfact.core.script

/**
 * 两种 URL 匹配：
 * - `@match`  → Chrome match pattern（scheme、host、path 三段，`*` 通配）
 * - `@include/@exclude` → 通配串（`*` 匹配任意字符，含 `/`）
 *
 * 都编译成锚定正则并按 pattern 缓存，避免每个请求重复构造。
 */
object UrlPatterns {

    private val cache = HashMap<String, Regex?>(64)

    fun matchesMatchPattern(pattern: String, url: String): Boolean =
        regexFor("m:" + pattern) { buildMatchPattern(it) }?.containsMatchIn(url) ?: false

    fun matchesGlob(pattern: String, url: String): Boolean =
        regexFor("g:" + pattern) { buildGlob(it) }?.containsMatchIn(url) ?: false

    @Synchronized
    private fun regexFor(key: String, builder: (String) -> Regex?): Regex? {
        if (cache.containsKey(key)) return cache[key]
        val body = key.substring(2)
        val regex = runCatching { builder(body) }.getOrNull()
        cache[key] = regex
        return regex
    }

    /** Chrome match pattern；返回 null 表示 pattern 非法（该条规则忽略）。 */
    private fun buildMatchPattern(pattern: String): Regex? {
        val p = pattern.trim()
        if (p.isEmpty()) return null
        if (p == "<all_urls>") return Regex("^(?:https?|file|ftp|wss?)://.*$", RegexOption.IGNORE_CASE)

        val schemeEnd = p.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = p.substring(0, schemeEnd)
        val rest = p.substring(schemeEnd + 3)
        val slash = rest.indexOf('/')
        val hostPart = if (slash < 0) rest else rest.substring(0, slash)
        val pathPart = if (slash < 0) "/" else rest.substring(slash)
        if (hostPart.isEmpty()) return null
        if (!pathPart.startsWith("/")) return null

        val sb = StringBuilder("^")
        sb.append(if (scheme == "*") "(?:https?|wss?)" else escapeLiteral(scheme))
        sb.append("://")
        when {
            hostPart == "*" -> sb.append("[^/?#]+")
            hostPart.startsWith("*.") -> {
                sb.append("(?:[^./?#]+\\.)*")
                sb.append(escapeLiteral(hostPart.substring(2)))
            }
            hostPart.startsWith(".") -> {
                sb.append("(?:[^./?#]+\\.)*")
                sb.append(escapeLiteral(hostPart.substring(1)))
            }
            else -> sb.append(escapeLiteral(hostPart))
        }
        sb.append("(?::\\d+)?")
        sb.append(globToRegexBody(pathPart))
        sb.append("$")
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }

    private fun buildGlob(pattern: String): Regex? {
        val p = pattern.trim()
        if (p.isEmpty()) return null
        val sb = StringBuilder("^")
        if (p.startsWith("*://")) {
            sb.append("(?:https?|wss?)://")
            sb.append(globToRegexBody(p.substring(4)))
        } else if (p.startsWith("//")) {
            sb.append("(?:https?|wss?):")
            sb.append(globToRegexBody(p))
        } else {
            sb.append(globToRegexBody(p))
        }
        sb.append("$")
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }

    /** `*` → `.*`，`?` → `.`，其它字符转义。 */
    private fun globToRegexBody(pattern: String): String {
        val sb = StringBuilder(pattern.length * 2)
        for (c in pattern) {
            when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                else -> appendEscaped(sb, c)
            }
        }
        return sb.toString()
    }

    private fun escapeLiteral(text: String): String {
        val sb = StringBuilder(text.length * 2)
        for (c in text) appendEscaped(sb, c)
        return sb.toString()
    }

    private fun appendEscaped(sb: StringBuilder, c: Char) {
        if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_' || c == '~') {
            sb.append(c)
            return
        }
        when (c) {
            '\\', '.', '+', '(', ')', '[', ']', '{', '}', '^', '$', '|', '?', '*', '/' -> sb.append('\\').append(c)
            else -> sb.append(c)
        }
    }
}
