package cn.olfact.download

import android.content.Context
import android.os.Environment
import cn.olfact.download.util.Files
import java.io.File

/**
 * 下载目录。默认放应用专属外部目录（免存储权限），
 * 系统 DownloadManager 走它自己的公共 Downloads；两边都可用时由调用方选择。
 */
object DownloadPaths {

    fun downloadsRoot(context: Context): File {
        val external = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        return File(external ?: File(context.filesDir, "downloads"), "olfact")
    }

    fun tempRoot(context: Context): File = File(context.cacheDir, "downloads/tmp")

    fun resolveTarget(context: Context, spec: DownloadSpec): File {
        val root = if (spec.subDir.isBlank()) downloadsRoot(context) else File(downloadsRoot(context), spec.subDir)
        val extension = Files.extensionOf(spec.url, spec.mimeType)
        val base = Files.sanitizeFileName(spec.fileName.ifBlank { "video" }, "video")
        val plain = File(root, Files.ensureExtension(base, extension))
        // 文件名已存在时也走 uniqueFile：不能因为 allowDuplicate=false 就覆盖旧文件。
        // allowDuplicate=false 只用于「同 URL 活跃任务去重」，不代表允许覆盖磁盘文件。
        if (spec.allowDuplicate || plain.exists()) {
            return Files.uniqueFile(root, base, extension)
        }
        return plain
    }

    /**
     * 单个任务的临时目录。
     *
     * recordId 来自 queue.json（可能损坏），直接拼进路径的话，
     * `remove()` 里的 `deleteRecursively()` 会顺着 `../..` 删到应用私有目录外面。
     */
    fun workDir(context: Context, recordId: String): File =
        File(tempRoot(context), safeId(recordId))

    private fun safeId(id: String): String =
        id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "task" }

    /**
     * 把记录里的 filePath 还原成可删除的文件。
     *
     * 三形态：
     * - `content://…`：系统 DownloadManager 的产物，要用 ContentResolver 删；
     * - 绝对路径：自研下载器写的成品；
     * - 相对路径（如 `olfact/video.mp4`）：系统下载器入队时用的 subPath，
     *   相对于外部 `Download/` 目录。
     */
    fun resolveExisting(context: Context, path: String): File? {
        if (path.isBlank()) return null
        return if (path.startsWith("/")) File(path) else File(downloadsRoot(context).parentFile, path)
    }

    fun plannedName(spec: DownloadSpec): String {
        val extension = Files.extensionOf(spec.url, spec.mimeType)
        val base = Files.sanitizeFileName(spec.fileName.ifBlank { "video" }, "video")
        return Files.ensureExtension(base, extension)
    }
}
