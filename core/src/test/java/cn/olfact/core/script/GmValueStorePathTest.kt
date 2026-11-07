package cn.olfact.core.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * GM 存储的文件名来自脚本 id，而脚本 id 是页面上的 `.user.js` 提供的（外部输入）。
 * 和 `ScriptStore.fileId` 一样必须清洗，否则 `../` 能把 JSON 写到 gm 目录外面。
 */
class GmValueStorePathTest {

    @Test
    fun `脚本 id 带路径分隔符时不会写到 gm 目录外`() {
        val root = Files.createTempDirectory("olfact-gm").toFile()
        val gmDir = File(root, "gm").apply { mkdirs() }
        try {
            val store = GmValueStore(gmDir)

            store.set("../../evil", "token", "\"v\"")

            assertFalse("不能写到 gm 目录的同级", File(root, "evil.json").exists())
            assertTrue("落盘文件必须在 gm 目录里", gmDir.listFiles().orEmpty().all { it.parentFile == gmDir })
            assertEquals("\"v\"", store.get("../../evil", "token"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `正常 id 的读写不受影响`() {
        val dir = Files.createTempDirectory("olfact-gm-ok").toFile()
        try {
            val store = GmValueStore(dir)
            store.set("script-1_a", "k", "123")
            store.set("script-1_a", "flag", "true")

            assertEquals("123", store.get("script-1_a", "k"))
            assertEquals("true", store.get("script-1_a", "flag"))
            assertEquals(listOf("k", "flag"), store.keys("script-1_a"))
            assertTrue(File(dir, "script-1_a.json").exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
