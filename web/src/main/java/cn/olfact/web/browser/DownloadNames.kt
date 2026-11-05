package cn.olfact.web.browser

import java.net.URLDecoder

/**
 * 从 `Content-Disposition` 里取文件名。
 *
 * WebView 的 `DownloadListener` 会把响应头原样给到我们；不解析它的话，
 * `/download?id=123` 这类地址只能退化成「download」+ MIME 猜后缀，文件名完全丢。
 *
 * 支持三种常见形态（大小写不敏感）：
 * - `attachment; filename="video.mp4"`
 * - `attachment; filename=video.mp4`
 * - `attachment; filename*=UTF-8''%E8%A7%86%E9%A2%91.mp4`
 */
object DownloadNames {

    private val EXTENDED = Regex("""filename\*\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE)
    private val PLAIN = Regex("""filename\s*=\s*("([^"]*)"|[^;]+)""", RegexOption.IGNORE_CASE)

    fun fromContentDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        EXTENDED.find(header)?.groupValues?.getOrNull(1)?.trim()?.trim('"')?.let { value ->
            decodeExtended(value)?.let { return clean(it) }
        }
        PLAIN.find(header)?.let { match ->
            val quoted = match.groupValues.getOrNull(2).orEmpty()
            val raw = quoted.ifBlank { match.groupValues.getOrNull(1).orEmpty() }
            return clean(raw)
        }
        return null
    }

    /** `UTF-8''%E8%A7%86...`（也可能省略 charset）。 */
    private fun decodeExtended(value: String): String? {
        val charset = value.substringBefore("''").takeIf { value.contains("''") }?.ifBlank { "UTF-8" } ?: "UTF-8"
        val encoded = if (value.contains("''")) value.substringAfter("''") else value
        return runCatching { URLDecoder.decode(encoded, charset) }.getOrNull()
    }

    /** 去掉路径、控制字符与首尾引号；空串视为没有文件名。 */
    private fun clean(raw: String): String? {
        val name = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace("\r", "")
            .replace("\n", "")
            .trim()
            .trim('"')
            .trim()
        return name.ifBlank { null }
    }
}
