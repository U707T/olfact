package cn.olfact.download.util

import java.io.File
import java.util.Locale

object Files {

    private val ILLEGAL = Regex("""[\\/:*?"<>|\u0000-\u001F]""")

    fun sanitizeFileName(raw: String, fallback: String = "download"): String {
        val cleaned = ILLEGAL.replace(raw, "_").trim().trimEnd('.').take(MAX_NAME)
        return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") fallback else cleaned
    }

    fun extensionOf(url: String?, mime: String?): String {
        val path = url?.substringBefore('?')?.substringBefore('#')?.lowercase().orEmpty()
        val fromPath = path.substringAfterLast('.', "")
        if (fromPath.isNotEmpty() && fromPath.length <= 5 && fromPath.all { it.isLetterOrDigit() }) {
            return fromPath
        }
        return when (mime?.substringBefore(';')?.trim()?.lowercase()) {
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            "video/x-matroska" -> "mkv"
            "audio/mpeg" -> "mp3"
            "audio/mp4", "audio/aac" -> "m4a"
            "application/vnd.apple.mpegurl", "application/x-mpegurl" -> "m3u8"
            else -> ""
        }
    }

    /**
     * 补上扩展名，但**不重复补**：`video.mp4` + `mp4` 仍然是 `video.mp4`。
     *
     * 之前 `resolveTarget` 无条件拼 `$base.$extension`，用户在「添加下载」里把后缀
     * 填进文件名（或页面标题本身带扩展名）就会得到 `video.mp4.mp4`。
     */
    fun ensureExtension(name: String, extension: String): String {
        if (extension.isBlank()) return name
        val dot = name.lastIndexOf('.')
        if (dot > 0 && dot < name.length - 1) {
            val tail = name.substring(dot + 1)
            if (tail.length <= 5 && tail.all { it.isLetterOrDigit() }) return name
        }
        return "$name.$extension"
    }

    /** 同名文件自动加 (1)(2)…，不覆盖已有文件。 */
    fun uniqueFile(dir: File, baseName: String, extension: String): File {
        dir.mkdirs()
        val full = ensureExtension(baseName, extension)
        val dot = full.lastIndexOf('.')
        val hasExt = dot > 0 && dot < full.length - 1 && full.substring(dot + 1).length <= 5
        val stem = if (hasExt) full.substring(0, dot) else full
        val ext = if (hasExt) full.substring(dot) else ""
        var candidate = File(dir, full)
        var index = 1
        while (candidate.exists()) {
            candidate = File(dir, "$stem($index)$ext")
            index++
            if (index > 9999) break
        }
        return candidate
    }

    fun sizeOf(vararg files: File): Long = files.sumOf { if (it.exists()) it.length() else 0L }

    fun deleteQuietly(file: File?) {
        if (file == null || !file.exists()) return
        runCatching { file.delete() }
    }

    fun humanSize(bytes: Long): String {
        if (bytes < 0) return "--"
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    private const val MAX_NAME = 96
}
