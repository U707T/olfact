package cn.olfact.web.doh

import java.util.Locale

/**
 * 三档分流策略，语义见 TECH-PLAN §4.2。
 *
 * - [SMART] 国内域名走系统 DNS，国外/清单内走 DoH
 * - [ONLY_LISTED] 默认系统，清单内走 DoH
 * - [EXCLUDE_LISTED] 默认 DoH，清单内走系统
 */
enum class DohMode(val label: String) {
    SMART("智能分流"),
    ONLY_LISTED("仅指定域名"),
    EXCLUDE_LISTED("仅排除域名");

    companion object {
        fun fromName(name: String?): DohMode =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: SMART

        fun fromLabel(label: String?): DohMode =
            entries.firstOrNull { it.label == label } ?: SMART
    }
}

internal fun String.normalizeHost(): String =
    trim()
        .removePrefix("http://")
        .removePrefix("https://")
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .substringBefore(':')
        .trim('.')
        .lowercase(Locale.ROOT)
