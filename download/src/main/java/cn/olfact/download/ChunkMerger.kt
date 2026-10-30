package cn.olfact.download

import java.io.File

/** 按顺序把分块文件拼成完整文件。纯 IO，无 Android 依赖。 */
object ChunkMerger {

    private const val BUFFER = 64 * 1024

    fun merge(parts: List<File>, output: File, deleteParts: Boolean = false): File {
        require(parts.isNotEmpty()) { "没有分块可合并" }
        output.parentFile?.mkdirs()
        val buffer = ByteArray(BUFFER)
        output.outputStream().buffered(BUFFER).use { sink ->
            parts.forEach { part ->
                part.inputStream().buffered(BUFFER).use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        sink.write(buffer, 0, read)
                    }
                }
            }
        }
        if (deleteParts) parts.forEach { runCatching { it.delete() } }
        return output
    }

    fun expectedSize(parts: List<File>): Long = parts.sumOf { if (it.exists()) it.length() else 0L }
}
