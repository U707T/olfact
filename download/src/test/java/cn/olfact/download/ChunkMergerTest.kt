package cn.olfact.download

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChunkMergerTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `按顺序拼接分块并可选删除`() {
        val parts = listOf("AAA", "BB", "CCCC").mapIndexed { index, text ->
            temp.newFile("part$index").apply { writeText(text) }
        }
        val output = temp.newFile("merged.bin")
        ChunkMerger.merge(parts, output, deleteParts = true)

        assertEquals("AAABBCCCC", output.readText())
        assertEquals(9L, output.length())
        parts.forEach { assertEquals(false, it.exists()) }
    }

    @Test
    fun `空分块会被当作零长度处理`() {
        val a = temp.newFile("a").apply { writeText("x") }
        val b = temp.newFile("b").apply { writeText("") }
        val output = temp.newFile("out")
        ChunkMerger.merge(listOf(a, b), output)
        assertEquals("x", output.readText())
        assertEquals(1L, ChunkMerger.expectedSize(listOf(a, b)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `没有分块直接抛错`() {
        ChunkMerger.merge(emptyList(), temp.newFile("none"))
    }
}
