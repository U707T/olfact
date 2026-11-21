package cn.olfact.core.b4

import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import okio.FileSystem
import okio.Path.Companion.toOkioPath

fun writeAtomicTextFile(file: File, text: String) = StoreFiles.writeAtomic(file, text)

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

    /** 写完并同步同目录临时文件后再原子替换，失败时保留旧文件。 */
    fun writeAtomic(target: File, text: String) = writeAtomic(target) {
        it.write(text.toByteArray(StandardCharsets.UTF_8))
    }

    fun writeAtomic(target: File, write: (OutputStream) -> Unit) {
        val parent = target.parentFile ?: throw IOException("no parent: $target")
        if (!ensureDir(parent) && !parent.isDirectory) throw IOException("无法创建目录 $parent")
        val tmp = File.createTempFile("olfact-", ".tmp", parent)
        try {
            FileOutputStream(tmp).use { out ->
                // ZIP writer may close its stream; keep the descriptor open until sync succeeds.
                write(object : FilterOutputStream(out) {
                    override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
                    override fun close() = flush()
                })
                out.fd.sync()
            }
            FileSystem.SYSTEM.atomicMove(tmp.toOkioPath(), target.toOkioPath())
        } finally {
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
