package cn.olfact.core.script

import kotlinx.serialization.Serializable

@Serializable
enum class RunAt {
    DOCUMENT_START,
    DOCUMENT_END,
    DOCUMENT_IDLE,
    BODY_END;

    val metaName: String
        get() = when (this) {
            DOCUMENT_START -> "document-start"
            DOCUMENT_END -> "document-end"
            DOCUMENT_IDLE -> "document-idle"
            BODY_END -> "document-body"
        }

    companion object {
        fun parse(raw: String?): RunAt = when (raw?.trim()?.lowercase()) {
            "document-start" -> DOCUMENT_START
            "document-idle" -> DOCUMENT_IDLE
            "document-body", "body-end" -> BODY_END
            else -> DOCUMENT_END
        }
    }
}

/**
 * 一个已安装的 UserScript（元数据部分；脚本体在 `scripts/<id>.user.js`）。
 */
@Serializable
data class UserScript(
    val id: String,
    val name: String,
    val namespace: String = "",
    val version: String = "",
    val description: String = "",
    val author: String = "",
    val icon: String = "",
    val matches: List<String> = emptyList(),
    val includes: List<String> = emptyList(),
    val excludes: List<String> = emptyList(),
    val runAt: RunAt = RunAt.DOCUMENT_END,
    val grants: List<String> = emptyList(),
    val enabled: Boolean = true,
    val sourceUrl: String? = null,
    val updateUrl: String? = null,
    val downloadUrl: String? = null,
    val installedAt: Long = 0L,
    val updatedAt: Long = 0L,
    val sizeBytes: Int = 0
) {
    val hasGmApi: Boolean get() = grants.any { it.startsWith("GM_") || it.startsWith("GM.") }

    /** `@match` 优先，没有则看 `@include`；`@exclude` 一票否决。 */
    fun matches(url: String): Boolean {
        if (excludes.isNotEmpty() && excludes.any { UrlPatterns.matchesGlob(it, url) }) return false
        if (matches.isEmpty() && includes.isEmpty()) return true
        if (matches.any { UrlPatterns.matchesMatchPattern(it, url) }) return true
        if (includes.any { UrlPatterns.matchesGlob(it, url) }) return true
        return false
    }
}
