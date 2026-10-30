package cn.olfact.core.b4

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/** 订阅清单 / 脚本索引 / 备份状态共用的原子文件读写。 */
internal object StoreFiles {

    fun ensureDir(dir: File): Boolean = dir.isDirectory || dir.mkdirs()

    fun readText(file: File): String? {
        if (!file.isFile) return null
        return try {
            file.readText(StandardCharsets.UTF_8)
        } catch (t: Throwable) {
            B4Log.w("StoreFiles", "读取失败 ${file.name}", t)
            null
        }
    }

    /** 先写 `.tmp` 再 rename，断电/杀进程不会留下半截 JSON。 */
    fun writeAtomic(target: File, text: String) {
        val parent = target.parentFile ?: throw IOException("no parent: $target")
        ensureDir(parent)
        val tmp = File(parent, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(StandardCharsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (target.exists() && !target.delete()) {
            throw IOException("无法替换 ${target.absolutePath}")
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    fun deleteQuietly(file: File?): Boolean {
        if (file == null) return false
        return try {
            !file.exists() || file.deleteRecursively()
        } catch (t: Throwable) {
            false
        }
    }

    fun sizeOf(file: File): Long = try {
        if (file.isFile) file.length() else 0L
    } catch (t: Throwable) {
        0L
    }
}
