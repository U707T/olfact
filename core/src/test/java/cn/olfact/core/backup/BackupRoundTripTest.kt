package cn.olfact.core.backup

import cn.olfact.core.script.UserScript
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupRoundTripTest {

    private fun sampleData() = BackupData(
        settings = mapOf("theme" to "dark", "fontSize" to "16"),
        bookmarks = listOf(
            BookmarkRecord("b1", "Olfact", "https://olfact.cn", updatedAt = 100),
            BookmarkRecord("b2", "Example", "https://example.com", updatedAt = 200)
        ),
        history = listOf(HistoryRecord("h1", "页面", "https://example.com/p", visitedAt = 300, visitCount = 3)),
        scripts = listOf(
            ScriptRecord(
                meta = UserScript(id = "abc123", name = "去广告", version = "1.0", updatedAt = 400),
                body = "document.querySelectorAll('.ad').forEach(e => e.remove());"
            )
        ),
        subscriptions = listOf(SubscriptionRecord("builtin-ublite", "uBO Lite 内置", enabled = true)),
        adblockWhitelist = listOf("example.com", "olfact.cn")
    )

    @Test
    fun `写出的备份能原样读回`() {
        val data = sampleData()
        val out = ByteArrayOutputStream()
        val manifest = BackupWriter().write(data, out, "0.1.0")
        val bytes = out.toByteArray()

        assertEquals(BackupManifest.FORMAT, manifest.format)
        assertEquals(2, manifest.counts["bookmarks"])
        assertTrue(BackupReader.isLikelyBackup(bytes))

        val archive = BackupReader().read(bytes)
        assertEquals(manifest.sha256, archive.manifest.sha256)
        assertEquals(data.settings, archive.data.settings)
        assertEquals(data.bookmarks, archive.data.bookmarks)
        assertEquals(data.history, archive.data.history)
        assertEquals(data.subscriptions, archive.data.subscriptions)
        assertEquals(data.adblockWhitelist, archive.data.adblockWhitelist)
        assertEquals(1, archive.data.scripts.size)
        assertEquals("去广告", archive.data.scripts[0].meta.name)
        assertEquals(data.scripts[0].body, archive.data.scripts[0].body)
    }

    @Test
    fun `data json 被篡改会被拒绝`() {
        val dataJson = """{"settings":{"a":"1"}}""".toByteArray()
        val manifest = """{"format":"olfact-backup","version":1,"createdAt":1,"appVersion":"t","sha256":"deadbeef","counts":{}}"""
        val bytes = zipOf(
            "manifest.json" to manifest.toByteArray(),
            "data.json" to dataJson
        )
        try {
            BackupReader().read(bytes)
            fail("应当抛出 BackupFormatException")
        } catch (e: BackupFormatException) {
            assertTrue(e.message!!.contains("校验失败"))
        }
    }

    @Test
    fun `zip slip 与非法条目名被拒绝`() {
        val manifest = """{"format":"olfact-backup","version":1,"createdAt":1,"appVersion":"t","sha256":"","counts":{}}"""
        val bytes = zipOf(
            "manifest.json" to manifest.toByteArray(),
            "data.json" to """{"settings":{}}""".toByteArray(),
            "../evil.sh" to "rm -rf /".toByteArray()
        )
        try {
            BackupReader().read(bytes)
            fail("应当拒绝包含 ../ 的条目")
        } catch (e: BackupFormatException) {
            assertTrue(e.message!!.contains("非法条目名"))
        }
    }

    @Test
    fun `版本过高会被拒绝`() {
        val manifest = """{"format":"olfact-backup","version":99,"createdAt":1,"appVersion":"t","sha256":"","counts":{}}"""
        val bytes = zipOf(
            "manifest.json" to manifest.toByteArray(),
            "data.json" to """{}""".toByteArray()
        )
        try {
            BackupReader().read(bytes)
            fail("应拒绝未知版本")
        } catch (e: BackupFormatException) {
            assertTrue(e.message!!.contains("不受支持"))
        }
    }

    @Test
    fun `合并只增不减且按更新时间取舍`() {
        val current = listOf(BookmarkRecord("b1", "旧标题", "https://a.com", updatedAt = 100))
        val incoming = listOf(
            BookmarkRecord("b1", "新标题", "https://a.com", updatedAt = 200),
            BookmarkRecord("b2", "新增", "https://b.com", updatedAt = 150),
            BookmarkRecord("b3", "更旧", "https://c.com", updatedAt = 50)
        )
        val (merged, report) = BackupMerger.mergeById(
            current, incoming, { it.id }, { it.updatedAt }, "bookmarks", preferIncoming = true
        )
        assertEquals(3, merged.size)
        assertEquals(2, report.added)
        assertEquals(1, report.updated)
        assertEquals(0, report.skipped)
        assertEquals("新标题", merged.first { it.id == "b1" }.title)
        assertEquals("旧标题", current[0].title) // 原列表未被修改
    }

    @Test
    fun `设置默认只补缺不覆盖`() {
        val current = mapOf("a" to "1", "b" to "2")
        val incoming = mapOf("a" to "9", "c" to "3")
        val (keep, report) = BackupMerger.mergeSettings(current, incoming, overwrite = false)
        assertEquals("1", keep["a"])
        assertEquals("3", keep["c"])
        assertEquals(1, report.added)
        assertEquals(1, report.skipped)

        val (over, _) = BackupMerger.mergeSettings(current, incoming, overwrite = true)
        assertEquals("9", over["a"])
        assertEquals("2", over["b"])
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
