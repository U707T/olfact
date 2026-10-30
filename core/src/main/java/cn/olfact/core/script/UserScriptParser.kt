package cn.olfact.core.script

import cn.olfact.core.adblock.UrlTools
import java.security.MessageDigest
import java.util.Locale

/**
 * `// ==UserScript== ... // ==/UserScript==` 元数据解析。
 *
 * 宽容策略：缺 `@name` 用域名兜底；未知 @key 忽略；缺结束标记时把剩余内容当元数据（手抄脚本常见）。
 */
object UserScriptParser {

    data class Parsed(
        val meta: UserScript,
        val body: String,
        val warnings: List<String> = emptyList()
    )

    private const val HEADER_START = "==UserScript=="
    private const val HEADER_END = "==/UserScript=="
    private const val MAX_SOURCE_BYTES = 2 * 1024 * 1024

    fun parse(
        source: String,
        sourceUrl: String? = null,
        id: String? = null,
        now: Long = System.currentTimeMillis()
    ): Parsed? {
        if (source.isBlank()) return null
        if (source.length > MAX_SOURCE_BYTES) return null

        val start = source.indexOf(HEADER_START)
        if (start < 0) {
            val fallbackName = sourceUrl?.let { UrlTools.hostOf(it) }?.takeIf { it.isNotEmpty() } ?: "未命名脚本"
            return Parsed(
                meta = UserScript(
                    id = id ?: hashId(fallbackName, sourceUrl ?: fallbackName),
                    name = fallbackName,
                    sourceUrl = sourceUrl,
                    installedAt = now,
                    updatedAt = now,
                    sizeBytes = source.length
                ),
                body = source,
                warnings = listOf("没有 ==UserScript== 元数据块，按裸脚本安装")
            )
        }

        val end = source.indexOf(HEADER_END, start)
        val metaBlock: String
        val body: String
        if (end < 0) {
            metaBlock = source.substring(start + HEADER_START.length)
            body = ""
        } else {
            metaBlock = source.substring(start + HEADER_START.length, end)
            body = source.substring(end + HEADER_END.length)
        }

        var name = ""
        var namespace = ""
        var version = ""
        var description = ""
        var author = ""
        var icon = ""
        var runAt = RunAt.DOCUMENT_END
        var updateUrl: String? = null
        var downloadUrl: String? = null
        val matches = ArrayList<String>()
        val includes = ArrayList<String>()
        val excludes = ArrayList<String>()
        val grants = ArrayList<String>()

        for (rawLine in metaBlock.lineSequence()) {
            val line = rawLine.trim().removePrefix("//").removePrefix("*").trim()
            if (line.isEmpty() || !line.startsWith("@")) continue
            val space = line.indexOf(' ')
            val key = if (space < 0) line.substring(1) else line.substring(1, space)
            val value = if (space < 0) "" else line.substring(space + 1).trim()
            if (value.isEmpty() && key != "grant") continue
            when (key.lowercase(Locale.US)) {
                "name" -> name = value
                "namespace" -> namespace = value
                "version" -> version = value
                "description" -> description = value
                "author" -> author = value
                "icon" -> icon = value
                "match" -> if (value.isNotEmpty()) matches.add(value)
                "include" -> if (value.isNotEmpty()) includes.add(value)
                "exclude" -> if (value.isNotEmpty()) excludes.add(value)
                "run-at" -> runAt = RunAt.parse(value)
                "grant" -> if (value.isNotEmpty()) grants.add(value)
                "updateurl" -> updateUrl = value.ifBlank { null }
                "downloadurl" -> downloadUrl = value.ifBlank { null }
                else -> Unit
            }
        }

        val finalName = name.ifBlank {
            sourceUrl?.let { UrlTools.hostOf(it) }?.takeIf { it.isNotEmpty() } ?: "未命名脚本"
        }
        val meta = UserScript(
            id = id ?: hashId(namespace, finalName),
            name = finalName,
            namespace = namespace,
            version = version,
            description = description,
            author = author,
            icon = icon,
            matches = matches,
            includes = includes,
            excludes = excludes,
            runAt = runAt,
            grants = grants,
            enabled = true,
            sourceUrl = sourceUrl,
            updateUrl = updateUrl,
            downloadUrl = downloadUrl,
            installedAt = now,
            updatedAt = now,
            sizeBytes = source.length
        )
        return Parsed(meta, body.trimStart('\n', '\r'))
    }

    /** 同一 namespace+name 视为同一脚本，重复安装等于"更新"。 */
    fun hashId(namespace: String, name: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest("$namespace|$name".toByteArray())
        return digest.take(8).joinToString("") { String.format(Locale.US, "%02x", it) }
    }
}
