package cn.olfact.app.link

import java.net.URI
import java.net.URLEncoder

/**
 * 一条候选链接。[label] 是给人看的主机名，[url] 是补全 scheme 后的可打开地址。
 */
data class LinkCandidate(val url: String, val label: String)

/**
 * 文本提链（N3）。
 *
 * 场景：用户把一段聊天记录/文章粘进地址栏，里面混着 3 个链接。
 * 做法：先抓完整 http(s) URL，再在剩余文本里抓裸域名，去重、去尾标点、最多 5 条。
 *
 * 纯函数，无 Android 依赖，可单测（见 LinkExtractorTest）。
 */
object LinkExtractor {

    private const val TRAIL = "，。；：！？、（）【】「」《》〈〉…—.,;:!?)]}>\"'"

    /** 这些后缀是文件名，不是域名。 */
    private val FILE_EXT = setOf(
        "html", "htm", "php", "jsp", "asp", "aspx", "js", "css", "json", "xml", "txt", "md",
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "ico", "mp4", "m3u8", "mp3", "m4a",
        "pdf", "zip", "rar", "apk", "exe", "dmg", "woff", "ttf", "avi", "mkv", "flv"
    )

    private val FULL_URL = Regex("""(?i)\bhttps?://[^\s<>"'，。；：！？、（）【】「」《》]+""")

    private val BARE_DOMAIN = Regex(
        """(?i)(?<![\w@.\-])(?:[a-z0-9](?:[a-z0-9\-]{0,61}[a-z0-9])?\.)+[a-z]{2,24}(?![\w.])(?::\d{2,5})?(?:/[^\s<>"'，。；：！？、（）【】「」《》]*)?"""
    )

    /** 只判断「整段就是一个 http(s) URL」，与 [FULL_URL] 的「从文本里找」不同。 */
    private val HTTP_URL_ONLY = Regex("""(?i)^https?://\S+$""")

    /** `scheme://` 前缀，用于 [normalize] 判断是否要补 https。 */
    private val SCHEME = Regex("(?i)^[a-z][a-z0-9+.-]*://")

    /** 从任意文本里抽取链接，按出现顺序去重，最多 [max] 条。 */
    fun extract(text: String, max: Int = 5): List<LinkCandidate> {
        if (text.isBlank()) return emptyList()

        val found = mutableListOf<LinkCandidate>()
        val occupied = mutableListOf<IntRange>()

        FULL_URL.findAll(text).forEach { m ->
            val raw = trimTrailing(m.value)
            if (raw.length > "https://".length) {
                found += LinkCandidate(raw, hostOf(raw))
                occupied += m.range
            }
        }

        // 已匹配的完整 URL 用等长空格遮掉，避免裸域名规则二次命中
        val masked = StringBuilder(text)
        occupied.forEach { r -> for (i in r) masked.setCharAt(i, ' ') }

        BARE_DOMAIN.findAll(masked).forEach { m ->
            val raw = trimTrailing(m.value)
            if (raw.isEmpty()) return@forEach
            val tld = raw.substringBefore('/').substringAfterLast('.').substringBefore(':').lowercase()
            if (tld in FILE_EXT) return@forEach
            if (raw.none { it == '.' }) return@forEach
            found += LinkCandidate(normalize(raw), hostOf(raw))
        }

        val seen = HashSet<String>()
        return found
            .filter { seen.add(it.url.lowercase().trimEnd('/')) }
            .take(max)
    }

    /** 段文本里是否有可打开的东西（用于决定「打开」还是「搜索」）。 */
    fun looksLikeUrl(input: String): Boolean {
        val s = input.trim()
        if (s.isEmpty() || s.contains(' ')) return false
        if (HTTP_URL_ONLY.matches(s)) return true
        return BARE_DOMAIN.matches(s) &&
            s.substringAfterLast('.').substringBefore('/').substringBefore(':').lowercase() !in FILE_EXT
    }

    /** 补 scheme；`www.x.com` → `https://www.x.com`。 */
    fun normalize(raw: String): String {
        val s = trimTrailing(raw.trim())
        return if (SCHEME.containsMatchIn(s)) s else "https://$s"
    }

    fun hostOf(url: String): String = runCatching {
        URI(normalize(url)).host ?: url
    }.getOrElse { url }

    private fun trimTrailing(s: String): String {
        var end = s.length
        while (end > 0 && s[end - 1] in TRAIL) end--
        return s.substring(0, end)
    }
}

/** 搜索引擎：内置三家 + 自定义模板。 */
object SearchEngines {

    data class Engine(val id: String, val name: String, val template: String)

    val all = listOf(
        Engine("baidu", "百度", "https://www.baidu.com/s?wd=%s"),
        Engine("bing", "必应", "https://www.bing.com/search?q=%s"),
        Engine("google", "Google", "https://www.google.com/search?q=%s"),
        Engine("custom", "自定义", "")
    )

    fun byId(id: String): Engine = all.firstOrNull { it.id == id } ?: all.first()

    /** 输入框内容 → 可打开地址：像网址就直接开，否则走搜索。 */
    fun urlFor(input: String, engineId: String? = null): String {
        val text = input.trim()
        if (text.isEmpty()) return "about:blank"
        if (LinkExtractor.looksLikeUrl(text)) return LinkExtractor.normalize(text)
        val id = engineId ?: cn.olfact.app.data.AppSettings.get(cn.olfact.app.data.K.SEARCH_ENGINE)
        val template = if (id == "custom") {
            cn.olfact.app.data.AppSettings.get(cn.olfact.app.data.K.SEARCH_CUSTOM)
                .ifBlank { all.first().template }
        } else {
            byId(id).template
        }
        val encoded = URLEncoder.encode(text, "UTF-8")
        return if (template.contains("%s")) template.replace("%s", encoded) else template + encoded
    }
}
