package cn.olfact.app.data

import android.content.Context
import cn.olfact.app.BuildConfig
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
import cn.olfact.core.backup.FavoriteRecord
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

    private var appContext: Context? = null

    fun install(context: Context) {
        val app = context.applicationContext
        if (pipeline != null) return
        appContext = app

        val dir = File(app.filesDir, DIR_NAME).also { it.mkdirs() }
        localDir = dir

        val settings = AppSettings.repo as? DataStoreSettingsRepository
        val scriptStore = checkNotNull(ModuleBackends.scriptStore) { "脚本模块未初始化，不能生成完整备份" }
        val adblock = checkNotNull(ModuleBackends.adBlockManager) { "拦截模块未初始化，不能生成完整备份" }
        val store = BackupStateStore(File(app.filesDir, "backup-state.json"))
        stateStore = store

        val built = BackupPipeline(
            workDir = File(app.cacheDir, "backup-work"),
            providers = BackupProviders(
                settings = settings?.let { SettingsSectionImpl(it) },
                bookmarks = BookmarkSection(app),
                history = HistorySection(app),
                favorites = FavoriteSection(app),
                scripts = scriptStore?.let { ScriptBackupSection(it) },
                subscriptions = adblock?.subscriptionBackupSection(),
                whitelist = adblock?.whitelistBackupSection()
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
            if (interval != null) {
                scheduler.schedule(interval, requireNetwork = AppSettings.get(K.BACKUP_WEBDAV_URL).isNotBlank())
            } else scheduler.cancel()
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
            file.inputStream().use { p.importArchive(it, MergeOptions(overwriteSettings = true)) }
        }
        // 脚本/订阅/白名单恢复后让内存态跟上（否则界面还是旧的，注入也不生效）
        ModuleBackends.adBlockManager?.let { manager ->
            runCatching { manager.refresh() }
        }
        if (result.merged.any { it.section == "settings" }) {
            ModuleBackends.adBlockManager?.setEnabled(AppSettings.get(K.AD_ENABLED))
            (cn.olfact.core.api.OlfactServices.doh as? DohAdapter)?.let { doh ->
                if (AppSettings.get(K.DOH_ENABLED)) doh.startFromSettings() else doh.stop()
            }
            appContext?.let(::syncSchedule)
            RuntimeFlags.reloadPagesOnResume = true
        }
        return result
    }

    fun latestLocal(): File? = localFiles().firstOrNull()

    fun localFiles(): List<File> =
        localDir?.listFiles { f -> f.isFile && BackupPipeline.isBackupFileName(f.name) }
            ?.sortedByDescending { it.name }
            .orEmpty()

    fun state(): BackupState? = stateStore?.load()

    /** 只读预览：给「恢复前确认」显示份数。 */
    fun preview(file: File): BackupData? = runCatching {
        file.inputStream().use { BackupReader().read(it).data }
    }.getOrNull()

    // ------------------------------------------------------------------ 数据段

    private class SettingsSectionImpl(
        private val repo: DataStoreSettingsRepository
    ) : SettingsSection {
        override fun exportSettings(): Map<String, String> =
            repo.exportMap() - K.BACKUP_WEBDAV_PASS.name

        override suspend fun importSettings(values: Map<String, String>, overwrite: Boolean): MergeReport {
            // Keystore ciphertext is tied to its original installation; never replace this device's credential.
            val portable = values - K.BACKUP_WEBDAV_PASS.name
            val previousTarget = repo.get(K.BACKUP_WEBDAV_URL) to repo.get(K.BACKUP_WEBDAV_USER)
            val written = repo.importMap(portable, overwrite)
            val restoredTarget = repo.get(K.BACKUP_WEBDAV_URL) to repo.get(K.BACKUP_WEBDAV_USER)
            if (restoredTarget != previousTarget) {
                repo.importMap(mapOf(K.BACKUP_WEBDAV_PASS.name to "s:"), overwrite = true)
            }
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
            val imported = BookmarkStore.importAll(
                context,
                items.map {
                    Bookmark(
                        id = it.id.toLongOrNull() ?: 0L,
                        title = it.title,
                        url = it.url,
                        folder = it.folderId ?: "默认",
                        createdAt = it.createdAt
                    )
                }.filter { it.url.isNotBlank() }
            )
            return MergeReport("bookmarks", imported, 0, items.size - imported)
        }
    }

    private class HistorySection(private val context: Context) : ListSection<HistoryRecord> {
        override fun exportItems(): List<HistoryRecord> = HistoryStore.items.map {
            HistoryRecord(id = it.url, title = it.title, url = it.url, visitedAt = it.visitedAt)
        }

        override fun importItems(items: List<HistoryRecord>, options: MergeOptions): MergeReport {
            val now = System.currentTimeMillis()
            val imported = HistoryStore.importAll(
                context,
                items.map {
                    HistoryEntry(
                        title = it.title,
                        url = it.url,
                        visitedAt = it.visitedAt.takeIf { at -> at > 0 } ?: now
                    )
                }
            )
            return MergeReport("history", imported, 0, items.size - imported)
        }
    }

    private class FavoriteSection(private val context: Context) : ListSection<FavoriteRecord> {
        override fun exportItems(): List<FavoriteRecord> = FavoriteStore.items.map {
            FavoriteRecord(id = it.id.toString(), title = it.title, url = it.url, createdAt = it.at)
        }

        override fun importItems(items: List<FavoriteRecord>, options: MergeOptions): MergeReport {
            val imported = FavoriteStore.importAll(
                context,
                items.map {
                    FavoriteStore.Favorite(
                        id = it.id.toLongOrNull() ?: 0L,
                        title = it.title,
                        url = it.url,
                        at = it.createdAt
                    )
                }
            )
            return MergeReport("favorites", imported, 0, items.size - imported)
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
