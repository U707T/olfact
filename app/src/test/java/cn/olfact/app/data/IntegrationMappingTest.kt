package cn.olfact.app.data

import cn.olfact.core.api.UserScript
import cn.olfact.core.script.ScriptStore
import cn.olfact.download.DownloadEngine
import cn.olfact.download.DownloadRecord
import cn.olfact.download.DownloadSpec
import cn.olfact.download.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * 合并集成层的映射单测：`ModuleBackends` 里两处「模型对不上」的转换，
 * 出错会让界面显示错状态或脚本丢失，属于回归重点。
 */
class IntegrationMappingTest {

    // ---------------------------------------------------- 下载记录 → 面板任务

    @Test
    fun `下载记录映射到面板任务`() {
        val record = DownloadRecord(
            id = "r1",
            spec = DownloadSpec(
                url = "https://example.com/a.mp4",
                fileName = "a.mp4",
                pageUrl = "https://example.com/watch",
                referer = "https://example.com/watch"
            ),
            engine = DownloadEngine.CHUNKED,
            status = DownloadStatus.RUNNING,
            bytesDownloaded = 5_000_000,
            totalBytes = 10_000_000,
            filePath = "/storage/emulated/0/Download/olfact/a.mp4"
        )

        val task = record.toTask()
        assertEquals("r1", task.id)
        assertEquals("a.mp4", task.name)
        assertEquals("https://example.com/watch", task.pageUrl)
        assertEquals(50, task.progress)
        assertEquals(5_000_000, task.doneBytes)
        assertEquals(10_000_000, task.totalBytes)
        assertEquals("/storage/emulated/0/Download/olfact/a.mp4", task.savePath)
        assertEquals(cn.olfact.core.api.DownloadState.RUNNING, task.state)
    }

    @Test
    fun `未知大小与终端状态`() {
        val done = DownloadRecord(
            id = "r2",
            spec = DownloadSpec(url = "https://example.com/b.m3u8", fileName = "b.m3u8"),
            engine = DownloadEngine.M3U8,
            status = DownloadStatus.COMPLETED,
            bytesDownloaded = 123,
            totalBytes = -1L,
            filePath = null
        ).toTask()

        assertEquals(cn.olfact.core.api.DownloadState.DONE, done.state)
        assertEquals(0, done.progress)          // 大小未知时不显示假进度
        assertEquals(0L, done.totalBytes)       // -1 不能漏给界面
        assertTrue(done.finishedAt > 0L)        // 终端状态要有完成时间
        assertEquals("", done.savePath)
        assertNull(done.error)
    }

    // ------------------------------------------------------ 脚本面板 ↔ B4 仓库

    @Test
    fun `脚本能在面板模型与仓库之间往返`() {
        val dir = Files.createTempDirectory("olfact-scripts").toFile()
        try {
            val adapter = ScriptAdapter(ScriptStore(dir))
            adapter.save(
                UserScript(
                    id = "s1",
                    name = "去广告",
                    version = "1.2",
                    matches = listOf("https://example.com/*"),
                    enabled = true,
                    code = "console.log('hi')"
                )
            )

            val loaded = adapter.all().single()
            assertEquals("s1", loaded.id)
            assertEquals("去广告", loaded.name)
            assertEquals("1.2", loaded.version)
            assertEquals(listOf("https://example.com/*"), loaded.matches)
            assertEquals("console.log('hi')", loaded.code)
            assertTrue(loaded.enabled)
            assertTrue(loaded.runAtStart)          // 面板默认按 document-start 注入

            adapter.setEnabled("s1", false)
            assertEquals(false, adapter.all().single().enabled)

            adapter.remove("s1")
            assertTrue(adapter.all().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `编辑脚本不会丢脚本体`() {
        val dir = Files.createTempDirectory("olfact-scripts-edit").toFile()
        try {
            val store = ScriptStore(dir)
            val adapter = ScriptAdapter(store)
            adapter.save(UserScript(id = "s2", name = "旧名字", code = "v1()"))
            adapter.save(UserScript(id = "s2", name = "新名字", code = "v2()"))

            val only = adapter.all().single()
            assertEquals("新名字", only.name)
            assertEquals("v2()", only.code)
        } finally {
            dir.deleteRecursively()
        }
    }
}
