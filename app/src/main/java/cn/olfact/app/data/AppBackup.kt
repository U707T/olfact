package cn.olfact.app.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
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
        scope.launch {
            try {
                WebDavSecret.migrateLegacy()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("AppBackup", "旧 WebDAV 密码迁移失败，保留原值等待重试", e)
            }
            syncSchedule(app)
        }
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
        WebDavSecret.migrateLegacy()
        val result = runner.run(trigger)
        if (result.success) AppSettings.set(K.BACKUP_LAST_AT, result.at)
        return result
    }

    /** 恢复最近一份本地备份（zip）。 */
    suspend fun restoreLatest(): ImportResult? {
        val file = latestLocal() ?: return null
        return restore(file)
    }

    suspend fun restore(file: File): ImportResult = restoreArchive { file.inputStream() }

    /** SAF 的一次性读授权足够完成本次恢复；不保存 URI，不申请整盘存储权限。 */
    suspend fun restore(context: Context, uri: Uri): ImportResult = restoreArchive {
        context.applicationContext.contentResolver.openInputStream(uri)
            ?: throw IOException("无法读取所选 ZIP，请重新选择文件")
    }

    /** 始终先生成本地备份，再以固定缓冲区流式导出；WebDAV 配置不影响此入口。 */
    suspend fun exportLocal(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val resolver = context.applicationContext.contentResolver
        var complete = false
        try {
            val runner = checkNotNull(pipeline) { "备份未初始化" }
            val dir = checkNotNull(localDir) { "本地备份目录未初始化" }
            val result = runner.runLocal(dir, "manual-export")
            if (!result.success) throw IOException(result.error ?: "生成本地备份失败")
            currentCoroutineContext().ensureActive()
            File(result.location).inputStream().use { input ->
                (resolver.openOutputStream(uri, "wt") ?: throw IOException("无法写入所选位置")).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                }
            }
            currentCoroutineContext().ensureActive()
            complete = true
        } finally {
            // CreateDocument 新建的文档失败/取消后可能只写入了一部分，尽力移除，完整本地副本保留。
            if (!complete) runCatching { DocumentsContract.deleteDocument(resolver, uri) }
        }
    }

    suspend fun latestRemote(): cn.olfact.core.backup.DavEntry? = remoteClient()
        .list(WEBDAV_SUBDIR)
        .filter { !it.isDirectory && BackupPipeline.isBackupFileName(it.name) }
        .maxByOrNull { it.name }

    suspend fun restoreRemote(entry: cn.olfact.core.backup.DavEntry): ImportResult {
        require(!entry.isDirectory && BackupPipeline.isBackupFileName(entry.name)) { "无效的备份文件" }
        return restoreArchive { remoteClient().get("$WEBDAV_SUBDIR/${entry.name}").inputStream() }
    }

    suspend fun testRemote(): Boolean = remoteClient().test()

    private suspend fun restoreArchive(open: suspend () -> java.io.InputStream): ImportResult {
        val p = pipeline ?: return ImportResult(error = "备份未初始化")
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            open().use { p.importArchive(it, MergeOptions(overwriteSettings = true)) }
        }
        // 脚本/订阅/白名单恢复后让内存态跟上（否则界面还是旧的，注入也不生效）
        ModuleBackends.adBlockManager?.let { manager ->
            runCatching { manager.refresh() }
        }
        if (result.merged.any { it.section == "settings" }) {
            ModuleBackends.adBlockManager?.setEnabled(AppSettings.get(K.AD_ENABLED))
            (cn.olfact.core.api.OlfactServices.doh as? DohAdapter)?.let { doh ->
                doh.applySettingsAsync().await()
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
            val written = WebDavSecret.credentialLock.withLock {
                repo.importMap(portable, overwrite, clearOnChange = clearPasswordOnTargetChange)
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
            FavoriteRecord(
                id = it.id.toString(), title = it.title, url = it.url, createdAt = it.at,
                sourcePage = it.sourcePage, mediaType = it.mediaType, mimeType = it.mimeType
            )
        }

        override fun importItems(items: List<FavoriteRecord>, options: MergeOptions): MergeReport {
            val imported = FavoriteStore.importAll(
                context,
                items.map {
                    FavoriteStore.Favorite(
                        id = it.id.toLongOrNull() ?: 0L,
                        title = it.title,
                        url = it.url,
                        at = it.createdAt,
                        sourcePage = it.sourcePage,
                        mediaType = it.mediaType,
                        mimeType = it.mimeType
                    )
                }
            )
            return MergeReport("favorites", imported, 0, items.size - imported)
        }
    }

    // ------------------------------------------------------------------ 投递目标

    private fun target(app: Context, dir: File): BackupTarget {
        val client = configuredRemoteClient() ?: return BackupTarget.Local(dir)
        return BackupTarget.WebDav(client, dir = WEBDAV_SUBDIR)
    }

    private suspend fun remoteClient(): WebDavClient {
        WebDavSecret.migrateLegacy()
        return requireNotNull(configuredRemoteClient()) { "请先配置 WebDAV 地址" }
    }

    private fun configuredRemoteClient(): WebDavClient? {
        val settings = (AppSettings.repo as? DataStoreSettingsRepository)?.exportMap().orEmpty()
        fun read(key: cn.olfact.core.api.SettingsKey<String>) = settings[key.name]?.removePrefix("s:").orEmpty()
        val url = read(K.BACKUP_WEBDAV_URL).trim().takeIf { it.isNotEmpty() } ?: return null
        return WebDavClient(url, read(K.BACKUP_WEBDAV_USER).ifBlank { null }, WebDavSecret.decode(read(K.BACKUP_WEBDAV_PASS)))
    }

    suspend fun setRemoteTarget(key: cn.olfact.core.api.SettingsKey<String>, value: String) {
        require(key == K.BACKUP_WEBDAV_URL || key == K.BACKUP_WEBDAV_USER)
        val repo = checkNotNull(AppSettings.repo as? DataStoreSettingsRepository) { "设置尚未就绪" }
        WebDavSecret.credentialLock.withLock {
            repo.importMap(mapOf(key.name to "s:$value"), overwrite = true, clearOnChange = clearPasswordOnTargetChange)
        }
        appContext?.let(::syncSchedule)
    }

    private val clearPasswordOnTargetChange = listOf(K.BACKUP_WEBDAV_URL, K.BACKUP_WEBDAV_USER)
        .associate { it.name to setOf(K.BACKUP_WEBDAV_PASS.name) }
}

/**
 * WebDAV 密码：落盘前用 Keystore AES-GCM 加密（原来存在 DataStore 里是明文）。
 *
 * 兼容旧数据：读出来不是 `enc:` 前缀时当作明文，读取成功后自动改写为密文。
 */
object WebDavSecret {

    private const val PREFIX = "enc:"
    internal val credentialLock = Mutex()

    /** 在 IO 中加密成功后才提交；与用户修改目标/密码互斥，避免迁移覆盖新凭据。 */
    suspend fun migrateLegacy() = withContext(Dispatchers.IO) {
        credentialLock.withLock {
            val repo = AppSettings.repo as? DataStoreSettingsRepository ?: return@withLock
            val raw = repo.get(K.BACKUP_WEBDAV_PASS)
            if (raw.isBlank() || raw.startsWith(PREFIX)) return@withLock
            val encrypted = PREFIX + KeystoreCrypto.encryptString(raw)
            repo.importMap(mapOf(K.BACKUP_WEBDAV_PASS.name to "s:$encrypted"), overwrite = true)
        }
    }

    suspend fun write(plain: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        credentialLock.withLock {
            val repo = checkNotNull(AppSettings.repo as? DataStoreSettingsRepository) { "设置尚未就绪" }
            val encoded = if (plain.isEmpty()) "" else PREFIX + KeystoreCrypto.encryptString(plain)
            repo.importMap(mapOf(K.BACKUP_WEBDAV_PASS.name to "s:$encoded"), overwrite = true)
            Unit
        }
    }

    /** Read legacy plaintext for compatibility. New passwords are only written after encryption succeeds. */
    fun decode(raw: String): String? {
        if (raw.isBlank()) return null
        if (!raw.startsWith(PREFIX)) return raw
        return runCatching { KeystoreCrypto.decryptString(raw.removePrefix(PREFIX)) }.getOrNull()
    }

    fun masked(): String = if (AppSettings.get(K.BACKUP_WEBDAV_PASS).isBlank()) "未配置" else "••••••"
}
