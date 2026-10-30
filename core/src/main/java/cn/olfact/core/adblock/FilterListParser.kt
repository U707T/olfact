package cn.olfact.core.adblock

import cn.olfact.core.b4.B4Log

/**
 * 规则表解析器：一份文本 → 网络规则 + 元素隐藏规则。
 *
 * 设计取舍：
 * - 不认识/不支持的选项一律**丢弃整条规则**。宁可少拦，不能误杀（误杀会直接表现为网页白屏）。
 * - scriptlet（`##+js(...)`）、过程化过滤器（`:has-text()`）、样式注入（`#$#`）全部丢弃：
 *   它们需要页面侧运行时，TECH-PLAN §1.2 已明确不实现规则引擎。
 */
object FilterListParser {

    private const val MAX_PATTERN_LENGTH = 1024
    private const val MAX_SELECTOR_LENGTH = 512
    private const val MAX_DOMAIN_PART_LENGTH = 512

    private val HOSTS_FILE = Regex("^(?:0\\.0\\.0\\.0|127\\.0\\.0\\.1|::1)\\s+(\\S+)\\s*$")
    private val UNSUPPORTED_PSEUDO = listOf(
        ":has-text(", ":contains(", ":xpath(", ":style(", ":remove(", ":matches-",
        ":upward(", ":watch-attr(", ":min-text-length(", ":others(", ":if(", ":if-not(",
        ":nth-ancestor(", ":shadow(", ":remove-attr(", ":remove-class("
    )
    private val COSMETIC_MARKERS = listOf("#@?#", "#@\$#", "#@#", "#?#", "#\$#", "##")

    data class Result(
        val networkRules: List<NetworkRule>,
        val cosmeticRules: List<CosmeticRule>,
        val totalLines: Int,
        val skipped: Int,
        val skippedSamples: List<String>
    ) {
        val ruleCount: Int get() = networkRules.size + cosmeticRules.size

        companion object {
            val EMPTY = Result(emptyList(), emptyList(), 0, 0, emptyList())
        }
    }

    fun parse(text: CharSequence, sourceId: String, limit: Int = 20_000): Result {
        val network = ArrayList<NetworkRule>(1024)
        val cosmetic = ArrayList<CosmeticRule>(256)
        val badFilterKeys = HashSet<String>()
        val seen = HashSet<String>(2048)
        var totalLines = 0
        var skipped = 0
        val samples = ArrayList<String>(8)

        fun skip(line: String) {
            skipped++
            if (samples.size < 8) samples.add(line.take(120))
        }

        for (rawLine in text.lineSequence()) {
            totalLines++
            if (network.size + cosmetic.size >= limit) {
                skipped++
                continue
            }
            var line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("!") || line.startsWith("[") || line.startsWith("//")) continue
            if (line.startsWith("#") && COSMETIC_MARKERS.none { line.contains(it) }) continue

            // hosts 文件写法 → `||domain^`
            HOSTS_FILE.matchEntire(line)?.let { m ->
                val host = m.groupValues[1].lowercase()
                if (host.isNotEmpty() && !host.contains('/')) {
                    val rule = buildNetwork("||$host^", sourceId, false, "||$host^") ?: run {
                        skip(line); return@let
                    }
                    if (seen.add(rule.raw)) network.add(rule) else skipped++
                }
                return@let
            }
            if (HOSTS_FILE.matches(line)) continue

            val marker = COSMETIC_MARKERS.firstOrNull { line.contains(it) }
            if (marker != null && !line.startsWith("@@")) {
                val rule = parseCosmetic(line, marker, sourceId)
                if (rule == null) skip(line) else if (seen.add(rule.raw)) cosmetic.add(rule) else skipped++
                continue
            }
            if (line.startsWith("@@") || line.startsWith("|") || line.startsWith("/") || line[0].isLetterOrDigit() || line[0] == '*') {
                val rule = parseNetwork(line, sourceId)
                if (rule == null) {
                    skip(line)
                    continue
                }
                if (rule.raw.contains("\$badfilter")) {
                    badFilterKeys.add(rule.canonicalKey())
                    continue
                }
                if (seen.add(rule.raw)) network.add(rule) else skipped++
                continue
            }
            skip(line)
        }

        val filtered = if (badFilterKeys.isEmpty()) network else network.filterNot { it.canonicalKey() in badFilterKeys }
        if (skipped > 0) {
            B4Log.d("FilterList", "$sourceId: ${totalLines} 行，规则 ${filtered.size}+${cosmetic.size}，跳过 $skipped")
        }
        return Result(filtered, cosmetic, totalLines, skipped, samples)
    }

    // ---------------------------------------------------------------- network

    private fun parseNetwork(line: String, sourceId: String): NetworkRule? {
        if (line.length > MAX_PATTERN_LENGTH) return null
        val isException = line.startsWith("@@")
        val body = if (isException) line.substring(2) else line
        if (body.isEmpty()) return null

        val (main, optionsRaw) = splitOptions(body)
        if (main.isEmpty()) return null
        val opts = parseOptions(optionsRaw ?: "") ?: return null
        return buildNetwork(main, sourceId, isException, line, opts)
    }

    private fun buildNetwork(pattern: String, sourceId: String, isException: Boolean, raw: String, opts: Opts? = null): NetworkRule? {
        val o = opts ?: Opts()
        if (pattern.length > MAX_PATTERN_LENGTH) return null
        val regex = try {
            if (pattern.length > 2 && pattern.startsWith("/") && pattern.endsWith("/")) {
                PatternCompiler.compileRawRegex(pattern.substring(1, pattern.length - 1), o.matchCase)
            } else {
                PatternCompiler.compile(pattern, o.matchCase)
            }
        } catch (t: Throwable) {
            return null
        }
        return NetworkRule(
            raw = raw,
            pattern = pattern,
            isException = isException,
            regex = regex,
            types = o.types,
            typesExcluded = o.typesExcluded,
            thirdParty = o.thirdParty,
            domainsInclude = o.domainsInclude,
            domainsExcluded = o.domainsExcluded,
            important = o.important,
            matchCase = o.matchCase,
            sourceId = sourceId,
            bucketToken = UrlTools.bucketTokenOf(pattern)
        )
    }

    private fun splitOptions(body: String): Pair<String, String?> {
        val idx = body.lastIndexOf('$')
        if (idx <= 0) return body to null
        val tail = body.substring(idx + 1)
        if (tail.isEmpty() || tail.contains('/') || tail.contains(' ')) return body to null
        return body.substring(0, idx) to tail
    }

    private class Opts {
        val types = HashSet<ResourceType>(4)
        val typesExcluded = HashSet<ResourceType>(4)
        val domainsInclude = ArrayList<String>(2)
        val domainsExcluded = ArrayList<String>(2)
        var thirdParty: Boolean? = null
        var important = false
        var matchCase = false
    }

    /** 返回 null 表示「有无法识别的选项」→ 整条规则丢弃。 */
    private fun parseOptions(raw: String): Opts? {
        val o = Opts()
        if (raw.isEmpty()) return o
        for (tokenRaw in raw.split(',')) {
            val token = tokenRaw.trim()
            if (token.isEmpty()) continue
            when {
                token == "third-party" || token == "3p" -> o.thirdParty = true
                token == "~third-party" || token == "~3p" -> o.thirdParty = false
                token == "important" -> o.important = true
                token == "match-case" -> o.matchCase = true
                token == "badfilter" -> Unit
                token == "all" -> o.types.addAll(ResourceType.entries)
                token.startsWith("domain=") -> {
                    if (!parseDomains(token.substringAfter("domain="), o)) return null
                }
                token.startsWith("~") -> {
                    val name = token.substring(1)
                    val type = ResourceType.fromOption(name) ?: return null
                    o.typesExcluded.add(type)
                }
                else -> {
                    val type = ResourceType.fromOption(token) ?: return null
                    o.types.add(type)
                }
            }
        }
        return o
    }

    private fun parseDomains(value: String, o: Opts): Boolean {
        if (value.length > MAX_DOMAIN_PART_LENGTH) return false
        for (dRaw in value.split('|')) {
            val d = dRaw.trim().lowercase()
            if (d.isEmpty()) continue
            if (!isValidDomain(d.removePrefix("~"))) return false
            if (d.startsWith("~")) o.domainsExcluded.add(d.substring(1)) else o.domainsInclude.add(d)
        }
        return true
    }

    private fun isValidDomain(d: String): Boolean {
        if (d.isEmpty() || d.length > 255) return false
        var labels = 0
        for (part in d.split('.')) {
            if (part.isEmpty()) continue
            labels++
            if (!part.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '*' }) return false
        }
        return labels > 0
    }

    // --------------------------------------------------------------- cosmetic

    private fun parseCosmetic(line: String, marker: String, sourceId: String): CosmeticRule? {
        val idx = line.indexOf(marker)
        if (idx < 0) return null
        val domainPart = line.substring(0, idx)
        val selector = line.substring(idx + marker.length).trim()
        if (selector.isEmpty() || selector.length > MAX_SELECTOR_LENGTH) return null
        if (marker == "#?#" || marker == "#\$#" || marker == "#@?#" || marker == "#@\$#") return null
        if (selector.startsWith("+js(") || selector.startsWith("^")) return null
        if (selector.contains('{') || selector.contains('}') || selector.contains('\n')) return null
        if (UNSUPPORTED_PSEUDO.any { selector.contains(it) }) return null
        if (selector.startsWith(":") && !selector.startsWith("::")) {
            // 纯伪类选择器（如 `:has(...)`）不具备隐藏目标，丢掉
            if (selector.contains('(')) return null
        }

        val include = ArrayList<String>(2)
        val exclude = ArrayList<String>(2)
        if (domainPart.isNotEmpty()) {
            if (domainPart.length > MAX_DOMAIN_PART_LENGTH) return null
            for (part in domainPart.split(',')) {
                val d = part.trim().lowercase()
                if (d.isEmpty()) continue
                if (!isValidDomain(d.removePrefix("~"))) return null
                if (d.startsWith("~")) exclude.add(d.substring(1)) else include.add(d)
            }
        }
        return CosmeticRule(
            raw = line,
            selector = selector,
            domainsInclude = include,
            domainsExcluded = exclude,
            isException = marker.startsWith("#@"),
            sourceId = sourceId
        )
    }
}
