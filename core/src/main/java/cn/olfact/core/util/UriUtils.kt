package cn.olfact.core.util

import android.net.Uri

/**
 * URL 小工具。只做"够用"的判断，不追求 RFC 完整性。
 */
object UriUtils {

    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    /** 常见二级公共后缀，用于粗略的 eTLD+1（不引 PSL，省 300KB）。 */
    private val TWO_LEVEL_SUFFIXES = setOf(
        "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn", "ac.cn",
        "co.uk", "org.uk", "ac.uk", "gov.uk",
        "com.hk", "com.tw", "com.au", "co.jp", "co.kr", "com.sg",
        "com.br", "com.ru", "co.in", "com.mx"
    )

    fun isHttp(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.trim().lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    /** 取 host（小写、去端口、去 www 前缀以外保持原样）。 */
    fun host(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return runCatching { Uri.parse(url).host?.lowercase() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** 去掉 www. 的 host，用于规则匹配与去重。 */
    fun bareHost(url: String?): String? = host(url)?.removePrefix("www.")

    /** scheme://host[:port]，用于 Referer / Origin。 */
    fun origin(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "$scheme://$host$port"
    }

    /** 粗略 eTLD+1：a.b.example.com.cn -> example.com.cn */
    fun registrableDomain(host: String?): String? {
        if (host.isNullOrBlank()) return null
        val h = host.trim().trimEnd('.').lowercase()
        if (h.isEmpty()) return null
        if (IPV4.matches(h) || h.contains(':')) return h
        val parts = h.split('.')
        if (parts.size <= 2) return h
        val lastTwo = parts.takeLast(2).joinToString(".")
        val keep = if (lastTwo in TWO_LEVEL_SUFFIXES) 3 else 2
        return parts.takeLast(keep).joinToString(".")
    }

    /**
     * 地址栏输入归一化：
     * - 已带 scheme 原样返回
     * - `www.x.com`、`x.com/path`、`1.2.3.4:8080`、`localhost:3000` 补 https://
     * - 其它当搜索词，交给调用方拼搜索引擎
     */
    fun looksLikeUrl(input: String): Boolean {
        val s = input.trim()
        if (s.isEmpty() || s.contains(' ')) return false
        if (isHttp(s)) return true
        if (s.startsWith("about:") || s.startsWith("file:") || s.startsWith("data:")) return true
        val hostPart = s.substringBefore('/').substringBefore('?').substringBefore('#')
        val hostOnly = hostPart.substringBefore(':')
        if (hostOnly.isEmpty()) return false
        if (hostOnly == "localhost" || IPV4.matches(hostOnly)) return true
        return hostOnly.contains('.') && !hostOnly.startsWith('.') && !hostOnly.endsWith('.')
    }

    /** 补全 scheme；不是 URL 时返回 null，由调用方转搜索。 */
    fun normalize(input: String): String? {
        val s = input.trim()
        if (s.isEmpty()) return null
        if (isHttp(s)) return s
        if (s.startsWith("about:") || s.startsWith("file:") || s.startsWith("data:")) return s
        if (!looksLikeUrl(s)) return null
        return "https://$s"
    }

    /** 去掉 fragment，便于比较"同一页面"。 */
    fun stripFragment(url: String): String = url.substringBefore('#')

    /** 取路径末段作文件名，例如 a/b/c.mp4 -> c.mp4。 */
    fun fileName(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val path = runCatching { Uri.parse(url).path }.getOrNull() ?: return null
        return path.substringAfterLast('/', "").takeIf { it.isNotBlank() }
    }
}
