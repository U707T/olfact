package cn.olfact.web.doh

import java.util.Locale

/**
 * 一条分流规则。支持四种写法（TECH-PLAN §4.3）：
 *
 * ```
 * example.com          精确
 * *.google.com         子域通配（不含自身）
 * +.blogspot.com       含自身 + 所有子域
 * re:^ads?\..*\.cn$    正则（高级）
 * ```
 *
 * 优先级：精确 > 通配 > 正则。同优先级按书写顺序取第一条。
 */
sealed class DnsRule {

    abstract val text: String
    abstract val priority: Int
    abstract fun matches(host: String): Boolean

    data class Exact(override val text: String) : DnsRule() {
        private val domain = text.lowercase(Locale.ROOT)
        override val priority: Int get() = PRIORITY_EXACT
        override fun matches(host: String) = host.equals(domain, ignoreCase = true)
    }

    data class Wildcard(override val text: String) : DnsRule() {
        private val domain = text.removePrefix("*.").lowercase(Locale.ROOT)
        override val priority: Int get() = PRIORITY_WILDCARD
        override fun matches(host: String): Boolean {
            val h = host.lowercase(Locale.ROOT)
            return h != domain && h.endsWith(".$domain")
        }
    }

    data class DomainAndSub(override val text: String) : DnsRule() {
        private val domain = text.removePrefix("+.").lowercase(Locale.ROOT)
        override val priority: Int get() = PRIORITY_WILDCARD
        override fun matches(host: String): Boolean {
            val h = host.lowercase(Locale.ROOT)
            return h == domain || h.endsWith(".$domain")
        }
    }

    data class RegexRule(override val text: String, private val regex: Regex) : DnsRule() {
        override val priority: Int get() = PRIORITY_REGEX
        override fun matches(host: String) = regex.containsMatchIn(host.lowercase(Locale.ROOT))
    }

    companion object {
        const val PRIORITY_EXACT = 3
        const val PRIORITY_WILDCARD = 2
        const val PRIORITY_REGEX = 1

        /** 解析一行规则，非法或注释返回 null。 */
        fun parse(line: String): DnsRule? {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith("#") || trimmed.startsWith("!") || trimmed.startsWith("//")) return null
            val body = trimmed.substringBefore('#').substringBefore(" //").trim()
            if (body.isEmpty()) return null
            return when {
                body.startsWith("re:") -> {
                    val pattern = body.removePrefix("re:").trim()
                    if (pattern.isEmpty()) null
                    else runCatching { RegexRule(body, Regex(pattern, RegexOption.IGNORE_CASE)) }.getOrNull()
                }
                body.startsWith("+.") ->
                    body.removePrefix("+.").normalizeHost().takeIf { it.isNotEmpty() }?.let { DomainAndSub("+.$it") }
                body.startsWith("*.") ->
                    body.removePrefix("*.").normalizeHost().takeIf { it.isNotEmpty() }?.let { Wildcard("*.$it") }
                else ->
                    body.normalizeHost().takeIf { it.isNotEmpty() }?.let { Exact(it) }
            }
        }
    }
}

/** 国内域名判定：按顶级后缀 + 常用站点表，够用且不需要联网。 */
object DomesticSites {

    private val suffixes = setOf(
        "cn", "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn", "ac.cn", "mil.cn", "xin"
    )

    private val domains = setOf(
        "baidu.com", "bdstatic.com", "bdimg.com", "qq.com", "qpic.cn", "qlogo.cn", "tencent.com",
        "gtimg.com", "taobao.com", "tmall.com", "alicdn.com", "aliyun.com",
        "alipay.com", "alibaba.com", "aliimg.com", "jd.com", "360.cn", "so.com", "sohu.com",
        "sina.com", "sinaimg.cn", "weibo.com", "weibo.cn", "163.com", "126.net", "netease.com",
        "bilibili.com", "hdslb.com", "biliapi.net", "douyin.com", "bytedance.com", "toutiao.com",
        "ixigua.com", "zhihu.com", "zhimg.com", "csdn.net", "gitee.com", "jianshu.com",
        "meituan.com", "dianping.com", "ctrip.com", "qunar.com", "xiaomi.com",
        "mi.com", "miui.com", "huawei.com", "oppo.com", "vivo.com", "kuaishou.com",
        "iqiyi.com", "youku.com", "douban.com", "doubanio.com", "xunlei.com", "pconline.com.cn",
        "cnblogs.com", "oschina.net", "51cto.com", "runoob.com", "w3school.com.cn"
    )

    fun isDomestic(host: String): Boolean {
        val h = host.normalizeHost()
        if (h.isEmpty()) return false
        if (domains.any { h == it || h.endsWith(".$it") }) return true
        return suffixes.any { h == it || h.endsWith(".$it") }
    }
}

/**
 * 分流规则表。不可变；每次修改返回新实例，便于多线程无锁共享。
 */
class DnsRules private constructor(
    val mode: DohMode,
    val rules: List<DnsRule>,
    private val domestic: (String) -> Boolean
) {

    /** 命中的最高优先级规则；未命中返回 null。 */
    fun match(host: String): DnsRule? {
        val h = host.normalizeHost()
        if (h.isEmpty()) return null
        var best: DnsRule? = null
        for (rule in rules) {
            if (!rule.matches(h)) continue
            if (best == null || rule.priority > best.priority) best = rule
        }
        return best
    }

    fun contains(host: String): Boolean = match(host) != null

    /**
     * @param sticky 会话粘性：本标签页主域命中规则后，页内所有 host 都走 DoH
     */
    fun useDoh(host: String, sticky: Boolean = false): Boolean {
        val h = host.normalizeHost()
        if (h.isEmpty()) return false
        if (EcsSubnet.isIpLiteral(h)) return false
        if (sticky) return true
        val listed = contains(h)
        return when (mode) {
            DohMode.SMART -> if (listed) true else !domestic(h)
            DohMode.ONLY_LISTED -> listed
            DohMode.EXCLUDE_LISTED -> !listed
        }
    }

    /** 给地址栏长按菜单用的一句话说明。 */
    fun explain(host: String): String {
        val rule = match(host)
        val viaDoh = useDoh(host, false)
        val channel = if (viaDoh) "DoH" else "系统 DNS"
        return when {
            rule != null -> "$channel（命中规则 ${rule.text}）"
            mode == DohMode.SMART -> "$channel（${if (viaDoh) "国外域名" else "国内域名"}）"
            else -> channel
        }
    }

    fun withMode(newMode: DohMode): DnsRules = DnsRules(newMode, rules, domestic)

    fun withRules(newRules: List<DnsRule>): DnsRules =
        DnsRules(mode, newRules.distinctBy { it.text.lowercase(Locale.ROOT) }, domestic)

    /** 加一条“本站走 DoH”的规则。 */
    fun plus(host: String): DnsRules {
        val h = host.normalizeHost()
        if (h.isEmpty()) return this
        return withRules(rules + DnsRule.DomainAndSub("+.$h"))
    }

    fun minus(host: String): DnsRules {
        val h = host.normalizeHost()
        if (h.isEmpty()) return this
        return withRules(rules.filterNot { it.matches(h) })
    }

    fun toText(): String = buildString {
        appendLine("# olfact DoH rules · mode=${mode.name}")
        appendLine("# 语法：example.com / *.example.com / +.example.com / re:^ads?\\..*")
        rules.forEach { appendLine(it.text) }
    }

    fun isEmpty(): Boolean = rules.isEmpty()

    override fun toString(): String = "DnsRules(mode=$mode, rules=${rules.size})"

    companion object {
        fun of(
            mode: DohMode = DohMode.SMART,
            rules: List<DnsRule> = emptyList(),
            domestic: (String) -> Boolean = DomesticSites::isDomestic
        ): DnsRules = DnsRules(mode, rules.distinctBy { it.text.lowercase(Locale.ROOT) }, domestic)

        fun parse(
            text: String,
            mode: DohMode = DohMode.SMART,
            domestic: (String) -> Boolean = DomesticSites::isDomestic
        ): DnsRules = of(mode, text.lineSequence().mapNotNull(DnsRule::parse).toList(), domestic)

        fun builtin(mode: DohMode = DohMode.SMART): DnsRules = of(mode, BUILTIN.mapNotNull(DnsRule::parse))

        /** 被污染概率高的站点，开箱可用（一行一条，可整体导出到 txt）。 */
        val BUILTIN: List<String> = listOf(
            "+.google.com", "+.googleapis.com", "+.gstatic.com", "+.googleusercontent.com",
            "+.youtube.com", "+.ytimg.com", "+.ggpht.com", "+.googlevideo.com",
            "+.twitter.com", "+.x.com", "+.twimg.com", "+.t.co",
            "+.facebook.com", "+.fbcdn.net", "+.instagram.com", "+.cdninstagram.com",
            "+.whatsapp.com", "+.whatsapp.net",
            "+.telegram.org", "+.t.me", "+.telesco.pe", "+.telegra.ph",
            "+.wikipedia.org", "+.wikimedia.org", "+.wiktionary.org",
            "+.github.com", "+.githubusercontent.com", "+.githubassets.com",
            "+.reddit.com", "+.redd.it", "+.redditmedia.com",
            "+.medium.com", "+.blogspot.com", "+.wordpress.com", "+.tumblr.com",
            "+.nytimes.com", "+.bbc.com", "+.bbc.co.uk", "+.cnn.com",
            "+.theguardian.com", "+.reuters.com", "+.bloomberg.com", "+.wsj.com",
            "+.discord.com", "+.discordapp.com", "+.discord.gg",
            "+.openai.com", "+.anthropic.com", "+.claude.ai", "+.chatgpt.com",
            "+.pixiv.net", "+.niconico.jp", "+.nicovideo.jp",
            "+.soundcloud.com", "+.spotify.com", "+.twitch.tv",
            "+.protonmail.com", "+.proton.me", "+.dropbox.com",
            "+.archive.org", "+.duckduckgo.com", "+.stackoverflow.com",
            "+.npmjs.com", "+.npmjs.org", "+.pypi.org", "+.docker.com",
            "re:^([a-z0-9-]+\\.)*google\\.[a-z]{2,3}$"
        )
    }
}
