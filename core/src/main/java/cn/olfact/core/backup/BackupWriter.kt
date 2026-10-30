package cn.olfact.core.backup

import kotlinx.serialization.json.Json
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 备份写出：`zip = manifest.json + data.json + scripts/<id>.user.js`。
 *
 * 脚本体**不重复**放进 data.json（只留元数据），十万字的脚本也只存一份。
 */
class BackupWriter(
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true },
    private val clock: () -> Long = System::currentTimeMillis
) {

    fun write(data: BackupData, out: OutputStream, appVersion: String = ""): BackupManifest {
        val dataForJson = data.copy(scripts = data.scripts.map { it.copy(body = "") })
        val dataBytes = json.encodeToString(BackupData.serializer(), dataForJson).toByteArray(Charsets.UTF_8)
        val manifest = BackupManifest(
            createdAt = clock(),
            appVersion = appVersion,
            sha256 = sha256Hex(dataBytes),
            counts = mapOf(
                "settings" to data.settings.size,
                "bookmarks" to data.bookmarks.size,
                "history" to data.history.size,
                "scripts" to data.scripts.size,
                "subscriptions" to data.subscriptions.size,
                "whitelist" to data.adblockWhitelist.size
            )
        )
        val manifestBytes = json.encodeToString(BackupManifest.serializer(), manifest).toByteArray(Charsets.UTF_8)

        val zip = ZipOutputStream(BufferedOutputStream(out))
        zip.use { z ->
            z.putNextEntry(ZipEntry(MANIFEST_ENTRY)); z.write(manifestBytes); z.closeEntry()
            z.putNextEntry(ZipEntry(DATA_ENTRY)); z.write(dataBytes); z.closeEntry()
            for (script in data.scripts) {
                val body = script.body
                if (body.isEmpty()) continue
                z.putNextEntry(ZipEntry(scriptEntry(script.meta.id)))
                z.write(body.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return manifest
    }

    fun writeToFile(data: BackupData, file: File, appVersion: String = ""): BackupManifest {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { return write(data, it, appVersion) }
    }

    companion object {
        const val MANIFEST_ENTRY = "manifest.json"
        const val DATA_ENTRY = "data.json"
        const val SCRIPT_DIR = "scripts/"

        fun scriptEntry(id: String): String = SCRIPT_DIR + sanitizeId(id) + ".user.js"

        fun sanitizeId(id: String): String =
            id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "script" }

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                String.format(Locale.US, "%02x", it)
            }
    }
}
