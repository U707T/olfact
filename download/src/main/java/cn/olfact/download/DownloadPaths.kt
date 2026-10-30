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
        if (spec.allowDuplicate) return Files.uniqueFile(root, base, extension)
        return File(root, if (extension.isBlank()) base else "$base.$extension")
    }

    fun workDir(context: Context, recordId: String): File = File(tempRoot(context), recordId)

    fun plannedName(spec: DownloadSpec): String {
        val extension = Files.extensionOf(spec.url, spec.mimeType)
        val base = Files.sanitizeFileName(spec.fileName.ifBlank { "video" }, "video")
        return if (extension.isBlank()) base else "$base.$extension"
    }
}
