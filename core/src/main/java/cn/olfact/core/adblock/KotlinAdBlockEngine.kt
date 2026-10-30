package cn.olfact.core.adblock

import cn.olfact.core.b4.B4Log

/**
 * 降级匹配器：`.so` 不在时接管全部拦截。
 *
 * 性能做法：
 * - 网络规则按「pattern 里最长的字母数字片段」建倒排表，查一条 URL 只需要看几个桶 + generic 桶；
 * - 规则上限 [maxRules]（默认 2 万，任务书要求），超了优先保留能进桶的规则；
 * - 元素隐藏按域名后缀建表，查询时沿 `a.b.c` → `b.c` → `c` 逐级取。
 */
class KotlinAdBlockEngine(
    parsed: FilterListParser.Result,
    private val maxRules: Int = 20_000,
    private val maxGenericCosmetic: Int = 400
) : AdBlockEngine {

    private val buckets: Map<String, List<NetworkRule>>
    private val generic: List<NetworkRule>
    private val cosmeticByDomain: Map<String, List<String>>
    private val cosmeticGeneric: List<String>
    private val cosmeticExceptions: Map<String, Set<String>>
    private val cosmeticGenericExceptions: Set<String>

    override val kind: EngineKind = EngineKind.KOTLIN
    override val ruleCount: Int

    init {
        val all = parsed.networkRules
        val limited: List<NetworkRule> = if (all.size <= maxRules) {
            all
        } else {
            // 例外规则（@@）体积小、语义关键：先全部保留，剩下的名额再按原顺序填。
            val exceptions = all.filter { it.isException }
            val kept = ArrayList<NetworkRule>(maxRules)
            kept.addAll(exceptions.take(maxRules))
            for (r in all) {
                if (r.isException) continue
                if (kept.size >= maxRules) break
                kept.add(r)
            }
            kept
        }

        val bucketMap = HashMap<String, MutableList<NetworkRule>>(limited.size / 3 + 1)
        val genericList = ArrayList<NetworkRule>(64)
        for (r in limited) {
            val token = r.bucketToken
            if (token == null) {
                genericList.add(r)
            } else {
                bucketMap.getOrPut(token) { ArrayList(4) }.add(r)
            }
        }
        buckets = bucketMap
        generic = genericList

        val byDomain = HashMap<String, MutableList<String>>(128)
        val genericSet = LinkedHashSet<String>(256)
        val exMap = HashMap<String, MutableSet<String>>(32)
        val genericEx = HashSet<String>(16)
        for (r in parsed.cosmeticRules) {
            when {
                r.isException && r.domainsInclude.isEmpty() -> genericEx.add(r.selector)
                r.isException -> for (d in r.domainsInclude) exMap.getOrPut(d) { HashSet(2) }.add(r.selector)
                r.domainsInclude.isEmpty() -> genericSet.add(r.selector)
                else -> for (d in r.domainsInclude) byDomain.getOrPut(d) { ArrayList(4) }.add(r.selector)
            }
        }
        cosmeticByDomain = byDomain
        cosmeticGeneric = genericSet.toList()
        cosmeticExceptions = exMap
        cosmeticGenericExceptions = genericEx

        ruleCount = limited.size + parsed.cosmeticRules.size
        if (all.size > maxRules) {
            B4Log.w("KotlinEngine", "规则数 ${all.size} 超过上限 $maxRules，已截断（保留桶内规则优先）")
        }
    }

    override fun check(url: String, sourceUrl: String?, type: ResourceType): Boolean {
        if (buckets.isEmpty() && generic.isEmpty()) return false
        val u = UrlTools.stripFragment(url).lowercase()
        if (u.length < 4) return false
        val host = UrlTools.hostOf(u)
        val pageHost = sourceUrl?.let { UrlTools.hostOf(it) }?.takeIf { it.isNotEmpty() }
        val thirdParty = pageHost != null && host.isNotEmpty() && UrlTools.isThirdParty(host, pageHost)

        var blocked = false
        var importantBlock = false
        var exception = false

        fun eval(rule: NetworkRule) {
            if (!rule.matches(u, pageHost, type, thirdParty)) return
            when {
                rule.important && !rule.isException -> importantBlock = true
                rule.isException -> exception = true
                else -> blocked = true
            }
        }

        for (token in UrlTools.tokensOf(u)) {
            val list = buckets[token] ?: continue
            for (i in list.indices) eval(list[i])
        }
        for (i in generic.indices) eval(generic[i])

        // `important` 规则压过例外（uBO 语义）
        return importantBlock || (blocked && !exception)
    }

    override fun cosmeticSelectors(pageUrl: String): List<String> {
        val host = UrlTools.hostOf(pageUrl)
        if (host.isEmpty()) return emptyList()
        val suffixes = UrlTools.suffixesOf(host)

        val banned = HashSet<String>(cosmeticGenericExceptions)
        for (suffix in suffixes) {
            cosmeticExceptions[suffix]?.let { banned.addAll(it) }
        }

        val out = LinkedHashSet<String>(32)
        for (suffix in suffixes) {
            cosmeticByDomain[suffix]?.let { out.addAll(it) }
        }
        if (out.size < maxGenericCosmetic) {
            for (s in cosmeticGeneric) {
                if (out.size >= maxGenericCosmetic) break
                out.add(s)
            }
        }
        out.removeAll(banned)
        return out.toList()
    }
}
