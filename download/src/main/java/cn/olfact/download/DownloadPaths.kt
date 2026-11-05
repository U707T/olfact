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

    fun workDir(context: Context, recordId: String): File = File(tempRoot(context), recordId)

    fun plannedName(spec: DownloadSpec): String {
        val extension = Files.extensionOf(spec.url, spec.mimeType)
        val base = Files.sanitizeFileName(spec.fileName.ifBlank { "video" }, "video")
        return Files.ensureExtension(base, extension)
    }
}
