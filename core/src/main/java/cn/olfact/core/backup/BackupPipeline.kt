package cn.olfact.core.backup

import cn.olfact.core.b4.B4Log
import cn.olfact.core.b4.StoreFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
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

    override suspend fun run(trigger: String): BackupResult = runBackup(trigger, null)

    /** 用户导出始终生成本地 ZIP，不受自动备份的 WebDAV 目标影响。 */
    suspend fun runLocal(dir: File, trigger: String = "manual-local"): BackupResult =
        runBackup(trigger, BackupTarget.Local(dir))

    private suspend fun runBackup(trigger: String, forcedTarget: BackupTarget?): BackupResult = withContext(Dispatchers.IO) {
        val started = clock()
        var size = 0L
        val result = try {
            val data = collect()
            val context = currentCoroutineContext()
            context.ensureActive()
            val target = forcedTarget ?: targetProvider()
            val name = fileName(started)
            val location = when (target) {
                is BackupTarget.Local -> {
                    val file = File(target.dir, name)
                    StoreFiles.writeAtomic(file) { out ->
                        writer.write(data, out, appVersion)
                        context.ensureActive()
                    }
                    size = file.length()
                    pruneLocal(target.dir, target.retention)
                    file.absolutePath
                }

                is BackupTarget.WebDav -> {
                    val bytes = exportBytes(data)
                    size = bytes.size.toLong()
                    val client = target.client
                    context.ensureActive()
                    if (target.dir.isNotBlank()) client.mkcol(target.dir)
                    val remotePath = if (target.dir.isBlank()) name else "${target.dir.trimEnd('/')}/$name"
                    client.put(remotePath, bytes)
                    pruneWebDav(client, target.dir, target.retention)
                    "webdav:$remotePath"
                }
            }
            B4Log.d("Backup", "备份完成 $location（${size / 1024} KB）")
            BackupResult(true, size, location, trigger, started)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Exception) {
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
        favorites = providers.favorites?.exportItems() ?: emptyList(),
        scripts = providers.scripts?.exportItems() ?: emptyList(),
        subscriptions = providers.subscriptions?.exportItems() ?: emptyList(),
        adblockWhitelist = providers.whitelist?.exportWhitelist() ?: emptyList()
    )

    fun exportBytes(data: BackupData = collect()): ByteArray {
        val out = ByteArrayOutputStream(256 * 1024)
        writer.write(data, out, appVersion)
        return out.toByteArray()
    }

    suspend fun importBytes(bytes: ByteArray, options: MergeOptions = MergeOptions()): ImportResult =
        importArchive(ByteArrayInputStream(bytes), options)

    /** 先整体校验，任何格式错误都不动现有数据；写入失败时报告已完成的分段。 */
    suspend fun importArchive(input: InputStream, options: MergeOptions = MergeOptions()): ImportResult =
        withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            val archive = try {
                reader.read(input) { context.ensureActive() }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Exception) {
                B4Log.e("Backup", "备份文件校验失败", t)
                return@withContext ImportResult(error = t.message ?: "备份文件损坏")
            }
            val data = archive.data
            val reports = ArrayList<MergeReport>(7)
            suspend fun applySection(name: String, merge: suspend () -> MergeReport): ImportResult? {
                context.ensureActive()
                return try {
                    reports.add(merge())
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    B4Log.e("Backup", "恢复${name}失败", e)
                    ImportResult(reports, "恢复${name}失败；部分数据可能已合并：${e.message ?: e.javaClass.simpleName}")
                }
            }

            providers.settings?.let { section ->
                applySection("设置") { section.importSettings(data.settings, options.overwriteSettings) }
                    ?.let { return@withContext it }
            }
            providers.bookmarks?.let { section ->
                applySection("书签") { section.importItems(data.bookmarks, options) }?.let { return@withContext it }
            }
            providers.history?.let { section ->
                applySection("历史") { section.importItems(data.history, options) }?.let { return@withContext it }
            }
            providers.favorites?.let { section ->
                applySection("收藏") { section.importItems(data.favorites, options) }?.let { return@withContext it }
            }
            providers.scripts?.let { section ->
                applySection("脚本") { section.importItems(data.scripts, options) }?.let { return@withContext it }
            }
            providers.subscriptions?.let { section ->
                applySection("订阅") { section.importItems(data.subscriptions, options) }?.let { return@withContext it }
            }
            providers.whitelist?.let { section ->
                applySection("白名单") { section.importWhitelist(data.adblockWhitelist) }?.let { return@withContext it }
            }
            B4Log.d("Backup", "导入完成：${reports.joinToString { "${it.section}+${it.added}/~${it.updated}" }}")
            ImportResult(merged = reports)
        }

    // ---------------------------------------------------------------- 保留策略

    private fun pruneLocal(dir: File, retention: Int) {
        if (retention <= 0) return
        val files = dir.listFiles { f -> f.isFile && isBackupFileName(f.name) }?.sortedByDescending { it.name } ?: return
        files.drop(retention).forEach { runCatching { it.delete() } }
    }

    private suspend fun pruneWebDav(client: WebDavClient, dir: String, retention: Int) {
        if (retention <= 0) return
        val entries = try {
            client.list(dir, 1)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            B4Log.w("Backup", "WebDAV 列表失败，跳过清理", e)
            return
        }
        entries.asSequence()
            .filter { !it.isDirectory && isBackupFileName(it.name) }
            .sortedByDescending { it.name }
            .drop(retention)
            .forEach { entry ->
                try {
                    if (!client.delete(if (dir.isBlank()) entry.name else "${dir.trimEnd('/')}/${entry.name}")) {
                        B4Log.w("Backup", "WebDAV 清理失败：${entry.name}")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    B4Log.w("Backup", "WebDAV 清理失败：${entry.name}", e)
                }
            }
    }

    companion object {
        const val FILE_PREFIX = "olfact-backup-"
        private val BACKUP_FILENAME = Regex("${FILE_PREFIX}[0-9]{8}-[0-9]{6}([0-9]{3})?\\.zip")

        fun isBackupFileName(name: String): Boolean = BACKUP_FILENAME.matches(name)

        fun fileName(timestamp: Long): String {
            val c = Calendar.getInstance()
            c.timeInMillis = timestamp
            val fmt = SimpleDateFormat("yyyyMMdd-HHmmssSSS", Locale.US)
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
