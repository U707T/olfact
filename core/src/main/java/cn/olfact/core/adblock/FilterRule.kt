package cn.olfact.core.adblock

/**
 * 一条网络规则（uBO / ABP 语法子集）。
 *
 * 匹配流程：类型过滤 → 域限定 → 三方判定 → 正则。短路顺序按代价从低到高排。
 */
class NetworkRule(
    val raw: String,
    val pattern: String,
    val isException: Boolean,
    val regex: Regex,
    val types: Set<ResourceType>,
    val typesExcluded: Set<ResourceType>,
    val thirdParty: Boolean?,
    val domainsInclude: List<String>,
    val domainsExcluded: List<String>,
    val important: Boolean,
    val matchCase: Boolean,
    val sourceId: String,
    val bucketToken: String?
) {
    fun matches(urlLower: String, pageHost: String?, type: ResourceType, thirdPartyRequest: Boolean): Boolean {
        if (typesExcluded.isNotEmpty() && type in typesExcluded) return false
        if (types.isNotEmpty() && type !in types) return false
        if (domainsInclude.isNotEmpty()) {
            if (pageHost == null || domainsInclude.none { UrlTools.hostMatches(pageHost, it) }) return false
        }
        if (pageHost != null && domainsExcluded.any { UrlTools.hostMatches(pageHost, it) }) return false
        val tp = thirdParty
        if (tp != null) {
            if (pageHost == null) return false
            if (tp != thirdPartyRequest) return false
        }
        return regex.containsMatchIn(urlLower)
    }

    /** 去掉 `$badfilter` 之后的规范化键，用于把 `badfilter` 规则对应回去。 */
    fun canonicalKey(): String {
        val optStart = raw.lastIndexOf('$')
        val base = if (optStart > 0) raw.substring(0, optStart) else raw
        val opts = if (optStart > 0) raw.substring(optStart + 1).split(',')
            .filter { it.isNotEmpty() && it != "badfilter" }
            .sorted()
            .joinToString(",") else ""
        return if (opts.isEmpty()) base else "$base\$$opts"
    }

    override fun toString(): String = raw
}

/** 一条元素隐藏规则（`##selector`）。scriptlet / 过程化过滤器已在解析阶段丢弃。 */
class CosmeticRule(
    val raw: String,
    val selector: String,
    val domainsInclude: List<String>,
    val domainsExcluded: List<String>,
    val isException: Boolean,
    val sourceId: String
) {
    fun appliesTo(host: String): Boolean {
        if (domainsExcluded.isNotEmpty() && domainsExcluded.any { UrlTools.hostMatches(host, it) }) return false
        if (domainsInclude.isEmpty()) return true
        return domainsInclude.any { UrlTools.hostMatches(host, it) }
    }

    override fun toString(): String = raw
}
