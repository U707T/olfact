package cn.olfact.core.backup

import cn.olfact.core.b4.B4Log
import cn.olfact.core.b4.StoreFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

sealed class BackupTarget {
    abstract val retention: Int

    /** 本地目录（App 私有目录或用户选的 SAF 目录）。 */
    data class Local(val dir: File, override val retention: Int = 5) : BackupTarget()

    /** WebDAV 目录（相对 baseUrl 的子目录）。 */
    data class WebDav(
        val client: WebDavClient,
        val dir: String = "olfact",
        override val retention: Int = 5
    ) : BackupTarget()
}

interface BackupRunner {
    suspend fun run(trigger: String): BackupResult
}

/**
 * 备份流水线：抽取 → 打包 → 投递（本地/WebDAV）→ 保留策略 → 记录状态。
 *
 * 所有 IO 在 Dispatchers.IO；失败不抛，返回 [BackupResult]，由 Worker 决定是否重试。
 */
class BackupPipeline(
    private val workDir: File,
    private val providers: BackupProviders,
    private val targetProvider: () -> BackupTarget,
    private val stateStore: BackupStateStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val writer: BackupWriter = BackupWriter(),
    private val reader: BackupReader = BackupReader(),
    private val appVersion: String = ""
) : BackupRunner {

    override suspend fun run(trigger: String): BackupResult = withContext(Dispatchers.IO) {
        val started = clock()
        val result = try {
            val data = collect()
            val bytes = exportBytes(data)
            val target = targetProvider()
            val name = fileName(started)
            val location = when (target) {
                is BackupTarget.Local -> {
                    StoreFiles.ensureDir(target.dir)
                    val file = File(target.dir, name)
                    file.writeBytes(bytes)
                    pruneLocal(target.dir, target.retention)
                    file.absolutePath
                }

                is BackupTarget.WebDav -> {
                    val client = target.client
                    runCatching { client.mkcol(target.dir) }
                    val remotePath = if (target.dir.isBlank()) name else "${target.dir.trimEnd('/')}/$name"
                    client.put(remotePath, bytes)
                    pruneWebDav(client, target.dir, target.retention)
                    "webdav:$remotePath"
                }
            }
            B4Log.d("Backup", "备份完成 $location（${bytes.size / 1024} KB）")
            BackupResult(true, bytes.size.toLong(), location, trigger, started)
        } catch (t: Throwable) {
            B4Log.e("Backup", "备份失败（$trigger）", t)
            BackupResult(false, 0L, "", trigger, started, t.message ?: t.javaClass.simpleName)
        }
        stateStore?.save(
            BackupState(
                lastRunAt = result.at,
                lastSuccess = result.success,
                lastBytes = result.bytes,
                lastLocation = result.location,
                lastTrigger = trigger,
                lastError = result.error
            )
        )
        result
    }

    fun collect(): BackupData = BackupData(
        settings = providers.settings?.exportSettings() ?: emptyMap(),
        bookmarks = providers.bookmarks?.exportItems() ?: emptyList(),
        history = providers.history?.exportItems() ?: emptyList(),
        scripts = providers.scripts?.exportItems() ?: emptyList(),
        subscriptions = providers.subscriptions?.exportItems() ?: emptyList(),
        adblockWhitelist = providers.whitelist?.exportWhitelist() ?: emptyList()
    )

    fun exportBytes(data: BackupData = collect()): ByteArray {
        val out = ByteArrayOutputStream(256 * 1024)
        writer.write(data, out, appVersion)
        return out.toByteArray()
    }

    /** 导入：先整体校验，再逐段合并；任何校验失败都不动现有数据。 */
    suspend fun importBytes(bytes: ByteArray, options: MergeOptions = MergeOptions()): ImportResult =
        withContext(Dispatchers.IO) {
            val archive = try {
                reader.read(bytes)
            } catch (t: Throwable) {
                B4Log.e("Backup", "备份文件校验失败", t)
                return@withContext ImportResult(error = t.message ?: "备份文件损坏")
            }
            val data = archive.data
            val reports = ArrayList<MergeReport>(6)

            providers.settings?.let {
                reports.add(it.importSettings(data.settings, options.overwriteSettings))
            }
            providers.bookmarks?.let {
                reports.add(it.importItems(data.bookmarks, options))
            }
            providers.history?.let {
                reports.add(it.importItems(data.history, options))
            }
            providers.scripts?.let {
                reports.add(it.importItems(data.scripts, options))
            }
            providers.subscriptions?.let {
                reports.add(it.importItems(data.subscriptions, options))
            }
            providers.whitelist?.let {
                reports.add(it.importWhitelist(data.adblockWhitelist))
            }
            B4Log.d("Backup", "导入完成：${reports.joinToString { "${it.section}+${it.added}/~${it.updated}" }}")
            ImportResult(merged = reports)
        }

    // ---------------------------------------------------------------- 保留策略

    private fun pruneLocal(dir: File, retention: Int) {
        if (retention <= 0) return
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) }?.sortedByDescending { it.name } ?: return
        files.drop(retention).forEach { runCatching { it.delete() } }
    }

    private suspend fun pruneWebDav(client: WebDavClient, dir: String, retention: Int) {
        if (retention <= 0) return
        val entries = runCatching { client.list(dir, 1) }.getOrElse {
            B4Log.w("Backup", "WebDAV 列表失败，跳过清理", it)
            return
        }
        entries.asSequence()
            .filter { !it.isDirectory && it.name.startsWith(FILE_PREFIX) }
            .sortedByDescending { it.name }
            .drop(retention)
            .forEach { runCatching { client.delete(if (dir.isBlank()) it.name else "${dir.trimEnd('/')}/${it.name}") } }
    }

    companion object {
        const val FILE_PREFIX = "olfact-backup-"

        fun fileName(timestamp: Long): String {
            val c = Calendar.getInstance()
            c.timeInMillis = timestamp
            val fmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            return "$FILE_PREFIX${fmt.format(c.time)}.zip"
        }
    }
}

class BackupStateStore(
    private val file: File,
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
) {

    @Synchronized
    fun load(): BackupState {
        val text = StoreFiles.readText(file) ?: return BackupState()
        return runCatching { json.decodeFromString(BackupState.serializer(), text) }.getOrDefault(BackupState())
    }

    @Synchronized
    fun save(state: BackupState) {
        runCatching { StoreFiles.writeAtomic(file, json.encodeToString(BackupState.serializer(), state)) }
            .onFailure { B4Log.w("BackupState", "写入状态失败", it) }
    }
}
