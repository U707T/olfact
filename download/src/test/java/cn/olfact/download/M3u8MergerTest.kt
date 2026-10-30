package cn.olfact.download

import cn.olfact.download.m3u8.M3u8Merger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class M3u8MergerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun segments(vararg contents: String): List<File> =
        contents.mapIndexed { index, text -> temp.newFile("seg$index.ts").apply { writeText(text) } }

    @Test
    fun `remux 成功时输出 mp4`() {
        val input = segments("A", "B")
        val output = File(temp.root, "out")
        val merger = M3u8Merger { files, file ->
            file.writeText("MP4:" + files.joinToString("") { it.readText() })
            file
        }

        val outcome = merger.merge(input, output, null)

        assertTrue(outcome.remuxed)
        assertEquals("MP4:AB", outcome.file.readText())
        assertTrue(outcome.file.name.endsWith(".mp4"))
    }

    @Test
    fun `remux 失败时降级拼接 ts`() {
        val input = segments("A", "B", "C")
        val output = File(temp.root, "fallback")
        val merger = M3u8Merger { _, _ -> throw IllegalStateException("不支持的编码") }

        val outcome = merger.merge(input, output, null)

        assertFalse(outcome.remuxed)
        assertEquals("ABC", outcome.file.readText())
        assertTrue(outcome.file.name.endsWith(".ts"))
        assertTrue(outcome.message.orEmpty().contains("降级"))
        assertFalse("失败的 mp4 不应残留", File(temp.root, "fallback.mp4").exists())
    }

    @Test
    fun `remux 输出为空也降级`() {
        val input = segments("X")
        val output = File(temp.root, "empty")
        val merger = M3u8Merger { _, file -> file.writeText(""); file }

        val outcome = merger.merge(input, output, null)

        assertFalse(outcome.remuxed)
        assertEquals("X", outcome.file.readText())
    }

    @Test
    fun `init 分片排在最前面`() {
        val init = temp.newFile("init.mp4").apply { writeText("INIT:") }
        val input = segments("A", "B")
        val output = File(temp.root, "with_init")
        val merger = M3u8Merger { files, file ->
            file.writeText(files.joinToString("") { it.readText() })
            file
        }

        val outcome = merger.merge(input, output, init)

        assertEquals("INIT:AB", outcome.file.readText())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `没有有效分片直接抛错`() {
        M3u8Merger { _, file -> file }.merge(emptyList(), File(temp.root, "none"), null)
    }
}
