package cn.olfact.core.backup

import cn.olfact.core.b4.B4Log
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 备份读取 + 校验。
 *
 * 校验顺序：条目名 → 条目上限 → manifest 格式/版本 → data.json 的 SHA-256 → 合并。
 * 任何一步失败都抛 [BackupFormatException]，**绝不半途导入**。
 */
class BackupReader(
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val maxTotalBytes: Long = 64L * 1024 * 1024,
    private val maxEntries: Int = 512,
    private val maxEntryBytes: Long = 32L * 1024 * 1024
) {

    data class Archive(val manifest: BackupManifest, val data: BackupData)

    fun read(bytes: ByteArray): Archive = read(ByteArrayInputStream(bytes))

    fun read(input: InputStream): Archive {
        val entries = HashMap<String, ByteArray>(16)
        var total = 0L
        var count = 0

        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++count > maxEntries) throw BackupFormatException("条目数超过上限 $maxEntries")
                val name = entry.name
                if (!isSafeEntryName(name)) throw BackupFormatException("非法条目名：$name")
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                var entryBytes = 0L
                while (true) {
                    val read = zip.read(chunk)
                    if (read < 0) break
                    entryBytes += read
                    total += read
                    if (entryBytes > maxEntryBytes) throw BackupFormatException("条目过大：$name")
                    if (total > maxTotalBytes) throw BackupFormatException("解压总量超过 ${maxTotalBytes / 1024 / 1024} MB")
                    buffer.write(chunk, 0, read)
                }
                entries[name] = buffer.toByteArray()
                zip.closeEntry()
            }
        }

        val manifestBytes = entries[BackupWriter.MANIFEST_ENTRY]
            ?: throw BackupFormatException("缺少 ${BackupWriter.MANIFEST_ENTRY}")
        val dataBytes = entries[BackupWriter.DATA_ENTRY]
            ?: throw BackupFormatException("缺少 ${BackupWriter.DATA_ENTRY}")

        val manifest = try {
            json.decodeFromString(BackupManifest.serializer(), manifestBytes.toString(Charsets.UTF_8))
        } catch (t: Throwable) {
            throw BackupFormatException("manifest 解析失败", t)
        }
        if (manifest.format != BackupManifest.FORMAT) {
            throw BackupFormatException("不是 olfact 备份（format=${manifest.format}）")
        }
        if (manifest.version < BackupManifest.MIN_SUPPORTED || manifest.version > BackupManifest.VERSION) {
            throw BackupFormatException("备份版本 ${manifest.version} 不受支持（当前 ${BackupManifest.VERSION}）")
        }
        val actual = BackupWriter.sha256Hex(dataBytes)
        if (manifest.sha256.isNotEmpty() && !actual.equals(manifest.sha256, ignoreCase = true)) {
            throw BackupFormatException("data.json 校验失败（备份可能损坏）")
        }

        val rawData = try {
            json.decodeFromString(BackupData.serializer(), dataBytes.toString(Charsets.UTF_8))
        } catch (t: Throwable) {
            throw BackupFormatException("data.json 解析失败", t)
        }

        val scripts = rawData.scripts.map { record ->
            val body = entries[BackupWriter.scriptEntry(record.meta.id)]?.toString(Charsets.UTF_8)
            if (body == null && record.body.isBlank()) {
                B4Log.w("BackupReader", "脚本 ${record.meta.name} 缺 body，跳过")
            }
            record.copy(body = body ?: record.body)
        }
        return Archive(manifest, rawData.copy(scripts = scripts))
    }

    private fun isSafeEntryName(name: String): Boolean {
        if (name.isEmpty() || name.length > 200) return false
        if (name.startsWith("/") || name.contains('\\')) return false
        if (name.split('/').any { it == ".." || it == "." }) return false
        return true
    }

    companion object {
        fun isLikelyBackup(bytes: ByteArray): Boolean =
            bytes.size > 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
    }
}
