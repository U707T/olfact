package cn.olfact.app.data

import android.content.Context
import cn.olfact.app.BuildConfig
import cn.olfact.core.adblock.SubscriptionStore
import cn.olfact.core.adblock.WhitelistStore
import cn.olfact.core.backup.AutoBackupScheduler
import cn.olfact.core.backup.BackupData
import cn.olfact.core.backup.BackupInterval
import cn.olfact.core.backup.BackupPipeline
import cn.olfact.core.backup.BackupProviders
import cn.olfact.core.backup.BackupReader
import cn.olfact.core.backup.BackupResult
import cn.olfact.core.backup.BackupRuntime
import cn.olfact.core.backup.BackupState
import cn.olfact.core.backup.BackupStateStore
import cn.olfact.core.backup.BackupTarget
import cn.olfact.core.backup.BookmarkRecord
import cn.olfact.core.backup.HistoryRecord
import cn.olfact.core.backup.ImportResult
import cn.olfact.core.backup.ListSection
import cn.olfact.core.backup.MergeOptions
import cn.olfact.core.backup.MergeReport
import cn.olfact.core.backup.ScriptBackupSection
import cn.olfact.core.backup.SettingsSection
import cn.olfact.core.backup.SubscriptionBackupSection
import cn.olfact.core.backup.WebDavClient
import cn.olfact.core.backup.WhitelistBackupSection
import cn.olfact.core.crypto.KeystoreCrypto
import cn.olfact.core.storage.DataStoreSettingsRepository
import kotlinx.coroutines.launch
import java.io.File

/**
 * 备份门面：把 B4 的 [BackupPipeline] 真正接上 B1 的设置、app 的书签/历史、B4 的脚本与拦截配置。
 *
 * 之前这里是一份读 SharedPreferences 的旧实现 —— 设置迁到 DataStore 后它读到的永远是空表，
 * 属于「备份看起来成功、实际什么都没存」，本轮修掉。
 */
object AppBackup {

    private const val DIR_NAME = "backups"
    private const val WEBDAV_SUBDIR = "olfact"

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    @Volatile
    private var pipeline: BackupPipeline? = null

    @Volatile
    private var localDir: File? = null

    @Volatile
    private var stateStore: BackupStateStore? = null

    fun install(context: Context) {
        val app = context.applicationContext
        if (pipeline != null) return

        val dir = File(app.filesDir, DIR_NAME).also { it.mkdirs() }
        localDir = dir

        val settings = AppSettings.repo as? DataStoreSettingsRepository
        val scriptStore = ModuleBackends.scriptStore
        val adblockDir = File(app.filesDir, "adblock")
        val store = BackupStateStore(File(app.filesDir, "backup-state.json"))
        stateStore = store

        val built = BackupPipeline(
            workDir = File(app.cacheDir, "backup-work"),
            providers = BackupProviders(
                settings = settings?.let { SettingsSectionImpl(it) },
                bookmarks = BookmarkSection(app),
                history = HistorySection(app),
                scripts = scriptStore?.let { ScriptBackupSection(it) },
                subscriptions = SubscriptionBackupSection(SubscriptionStore(adblockDir)),
                whitelist = WhitelistBackupSection(WhitelistStore(File(adblockDir, "whitelist.json")))
            ),
            targetProvider = { target(app, dir) },
            stateStore = store,
            appVersion = BuildConfig.VERSION_NAME
        )
        pipeline = built
        // AutoBackupWorker 通过这个全局点拿执行器
        BackupRuntime.runner = built

        // WorkManager 首次 getInstance 要建库，别放在 Application.onCreate 里等它
        scope.launch { syncSchedule(app) }
    }

    /** 「自动备份」三个档位 → WorkManager 周期任务。 */
    fun syncSchedule(context: Context) {
        val interval = when (AppSettings.get(K.BACKUP_AUTO)) {
            "daily" -> BackupInterval.H24
            "weekly" -> BackupInterval.WEEK
            else -> null
        }
        runCatching {
            val scheduler = AutoBackupScheduler(context.applicationContext)
            if (interval != null) scheduler.schedule(interval) else scheduler.cancel()
        }
    }

    suspend fun backupNow(trigger: String = "manual"): BackupResult {
        val runner = pipeline ?: return BackupResult(success = false, error = "备份未初始化", trigger = trigger)
        val result = runner.run(trigger)
        if (result.success) AppSettings.set(K.BACKUP_LAST_AT, result.at)
        return result
    }

    /** 恢复最近一份本地备份（zip）。 */
    suspend fun restoreLatest(): ImportResult? {
        val file = latestLocal() ?: return null
        return restore(file)
    }

    suspend fun restore(file: File): ImportResult {
        val p = pipeline ?: return ImportResult(error = "备份未初始化")
        // 读 zip 放到 IO：备份包可能几十 MB，不能占主线程。
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            p.importBytes(file.readBytes(), MergeOptions(overwriteSettings = true))
        }
        // 脚本/订阅/白名单恢复后让内存态跟上（否则界面还是旧的，注入也不生效）
        ModuleBackends.adBlockManager?.let { manager ->
            runCatching { manager.reload() }
        }
        return result
    }

    fun latestLocal(): File? = localFiles().firstOrNull()

    fun localFiles(): List<File> =
        localDir?.listFiles { f -> f.isFile && f.name.startsWith(BackupPipeline.FILE_PREFIX) }
            ?.sortedByDescending { it.name }
            .orEmpty()

    fun state(): BackupState? = stateStore?.load()

    /** 只读预览：给「恢复前确认」显示份数。 */
    fun preview(file: File): BackupData? = runCatching {
        BackupReader().read(file.readBytes()).data
    }.getOrNull()

    // ------------------------------------------------------------------ 数据段

    private class SettingsSectionImpl(
        private val repo: DataStoreSettingsRepository
    ) : SettingsSection {
        override fun exportSettings(): Map<String, String> = repo.exportMap()

        override fun importSettings(values: Map<String, String>, overwrite: Boolean): MergeReport {
            val written = repo.importMap(values, overwrite)
            return MergeReport("settings", added = written, updated = 0, skipped = values.size - written)
        }
    }

    private class BookmarkSection(private val context: Context) : ListSection<BookmarkRecord> {
        override fun exportItems(): List<BookmarkRecord> = BookmarkStore.all().map {
            BookmarkRecord(
                id = it.id.toString(),
                title = it.title,
                url = it.url,
                folderId = it.folder,
                createdAt = it.createdAt
            )
        }

        override fun importItems(items: List<BookmarkRecord>, options: MergeOptions): MergeReport {
            var added = 0
            var skipped = 0
            val known = BookmarkStore.all().mapTo(mutableSetOf()) { it.url }
            for (record in items) {
                if (record.url.isBlank() || !known.add(record.url)) {
                    skipped++
                    continue
                }
                BookmarkStore.add(context, record.title, record.url, record.folderId ?: "默认")
                added++
            }
            return MergeReport("bookmarks", added, 0, skipped)
        }
    }

    private class HistorySection(private val context: Context) : ListSection<HistoryRecord> {
        override fun exportItems(): List<HistoryRecord> = HistoryStore.items.map {
            HistoryRecord(id = it.url, title = it.title, url = it.url, visitedAt = it.visitedAt)
        }

        override fun importItems(items: List<HistoryRecord>, options: MergeOptions): MergeReport {
            var added = 0
            var skipped = 0
            val known = HistoryStore.items.mapTo(mutableSetOf()) { it.url }
            for (record in items) {
                if (record.url.isBlank() || !known.add(record.url)) {
                    skipped++
                    continue
                }
                HistoryStore.record(context, record.title, record.url)
                added++
            }
            return MergeReport("history", added, 0, skipped)
        }
    }

    // ------------------------------------------------------------------ 投递目标

    private fun target(app: Context, dir: File): BackupTarget {
        val url = AppSettings.get(K.BACKUP_WEBDAV_URL).trim()
        if (url.isBlank()) return BackupTarget.Local(dir)
        val client = WebDavClient(
            baseUrl = url,
            username = AppSettings.get(K.BACKUP_WEBDAV_USER).ifBlank { null },
            password = WebDavSecret.read()
        )
        return BackupTarget.WebDav(client, dir = WEBDAV_SUBDIR)
    }
}

/**
 * WebDAV 密码：落盘前用 Keystore AES-GCM 加密（原来存在 DataStore 里是明文）。
 *
 * 兼容旧数据：读出来不是 `enc:` 前缀时当作明文，读取成功后自动改写为密文。
 */
object WebDavSecret {

    private const val PREFIX = "enc:"

    fun write(plain: String) {
        if (plain.isEmpty()) {
            AppSettings.set(K.BACKUP_WEBDAV_PASS, "")
            return
        }
        val encoded = runCatching { PREFIX + KeystoreCrypto.encryptString(plain) }.getOrElse { plain }
        AppSettings.set(K.BACKUP_WEBDAV_PASS, encoded)
    }

    fun read(): String? {
        val raw = AppSettings.get(K.BACKUP_WEBDAV_PASS)
        if (raw.isBlank()) return null
        if (!raw.startsWith(PREFIX)) {
            write(raw)
            return raw
        }
        return runCatching { KeystoreCrypto.decryptString(raw.removePrefix(PREFIX)) }.getOrNull()
    }

    fun masked(): String = if (read().isNullOrEmpty()) "未配置" else "••••••"
}
