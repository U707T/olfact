package cn.olfact.core.backup

import cn.olfact.core.script.ScriptStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BackupPipelineTest {

    private val scriptSource = """
        // ==UserScript==
        // @name         备份测试脚本
        // @namespace    olfact/test
        // @version      1
        // @match        *://*/*
        // ==/UserScript==
        console.log('hello from backup');
    """.trimIndent()

    @Test
    fun `本地备份 清数据 再恢复`() = runBlocking<Unit> {
        val root = Files.createTempDirectory("olfact-backup").toFile()
        val scriptsDir = File(root, "scripts")
        val backupDir = File(root, "out")

        val store = ScriptStore(scriptsDir)
        store.install(scriptSource, "https://example.com/x.user.js")
        val providers = BackupProviders(scripts = ScriptBackupSection(store))

        val pipeline = BackupPipeline(
            workDir = root,
            providers = providers,
            targetProvider = { BackupTarget.Local(backupDir, retention = 2) },
            stateStore = BackupStateStore(File(root, "state.json"))
        )
        val result = pipeline.run("manual")
        assertTrue("备份应成功：${result.error}", result.success)
        val files = backupDir.listFiles()!!.filter { it.name.endsWith(".zip") }
        assertEquals(1, files.size)

        // 模拟"清数据"：整目录删掉
        scriptsDir.deleteRecursively()
        assertEquals(0, ScriptStore(scriptsDir).count())

        // 恢复
        val bytes = files[0].readBytes()
        val freshStore = ScriptStore(scriptsDir)
        val restore = BackupPipeline(
            workDir = root,
            providers = BackupProviders(scripts = ScriptBackupSection(freshStore)),
            targetProvider = { BackupTarget.Local(backupDir) }
        ).importBytes(bytes)

        assertEquals(null, restore.error)
        assertTrue(restore.changed >= 1)
        assertEquals(1, freshStore.count())
        assertTrue(freshStore.body(freshStore.list()[0].id)!!.contains("hello from backup"))
        root.deleteRecursively()
    }

    @Test
    fun `损坏文件导入不会破坏现有数据`() = runBlocking<Unit> {
        val root = Files.createTempDirectory("olfact-backup2").toFile()
        val store = ScriptStore(File(root, "scripts"))
        store.install(scriptSource)
        val pipeline = BackupPipeline(
            workDir = root,
            providers = BackupProviders(scripts = ScriptBackupSection(store)),
            targetProvider = { BackupTarget.Local(File(root, "out")) }
        )
        val result = pipeline.importBytes("not a zip".toByteArray())
        assertTrue(result.error != null)
        assertEquals(1, store.count())
        root.deleteRecursively()
    }

    @Test
    fun `保留策略只留最近 N 份`() = runBlocking<Unit> {
        val root = Files.createTempDirectory("olfact-backup3").toFile()
        val outDir = File(root, "out")
        var now = 1_700_000_000_000L
        val pipeline = BackupPipeline(
            workDir = root,
            providers = BackupProviders(),
            targetProvider = { BackupTarget.Local(outDir, retention = 2) },
            clock = { now += 60_000; now }
        )
        repeat(4) {
            val r = pipeline.run("manual")
            assertTrue(r.success)
        }
        assertEquals(2, outDir.listFiles()!!.count { it.name.endsWith(".zip") })
        root.deleteRecursively()
    }

    @Test
    fun `只清理完整备份文件且同秒不同毫秒不会覆盖`() = runBlocking<Unit> {
        val root = Files.createTempDirectory("olfact-backup-boundary").toFile()
        try {
            val unrelated = File(root, "olfact-backup-not-a-backup.txt").also { it.writeText("keep") }
            val leftover = File(root, "olfact-backup-20200101-120000.zip.tmp").also { it.writeText("keep") }
            var now = 1_700_000_000_000L
            val pipeline = BackupPipeline(root, BackupProviders(), { BackupTarget.Local(root, retention = 2) }, clock = { now++ })
            repeat(2) { assertTrue(pipeline.run("manual").success) }
            assertEquals(2, root.listFiles()!!.count { BackupPipeline.isBackupFileName(it.name) })
            assertTrue(BackupPipeline.isBackupFileName("olfact-backup-20200101-120000.zip"))
            assertTrue(unrelated.isFile)
            assertTrue(leftover.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `恢复部分分段写失败返回现场而不是显示全部成功`() = runBlocking<Unit> {
        val root = Files.createTempDirectory("olfact-backup-partial").toFile()
        try {
            val providers = BackupProviders(
                settings = object : SettingsSection {
                    override fun exportSettings() = mapOf("theme" to "s:dark")
                    override suspend fun importSettings(values: Map<String, String>, overwrite: Boolean) = MergeReport("settings", added = 1)
                },
                bookmarks = object : ListSection<BookmarkRecord> {
                    override fun exportItems() = emptyList<BookmarkRecord>()
                    override fun importItems(items: List<BookmarkRecord>, options: MergeOptions): MergeReport =
                        throw java.io.IOException("不可写")
                }
            )
            val pipeline = BackupPipeline(root, providers, { BackupTarget.Local(root) })
            val result = pipeline.importArchive(pipeline.exportBytes().inputStream())
            assertTrue(result.error.orEmpty().contains("恢复书签失败"))
            assertEquals(listOf("settings"), result.merged.map { it.section })
        } finally {
            root.deleteRecursively()
        }
    }
}
