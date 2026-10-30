package cn.olfact.ai

import cn.olfact.ai.summarize.TextChunker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChunkerTest {

    @Test
    fun `短文本不切块`() {
        val chunks = TextChunker.chunk("hello", maxChars = 100)
        assertEquals(1, chunks.size)
        assertEquals("hello", chunks.first())
    }

    @Test
    fun `空文本返回空列表`() {
        assertTrue(TextChunker.chunk("   ").isEmpty())
    }

    @Test
    fun `每块不超过上限`() {
        val text = (1..50).joinToString("\n\n") { "第 $it 段。" + "内容".repeat(30) }
        val chunks = TextChunker.chunk(text, maxChars = 200, overlapChars = 0)
        assertTrue(chunks.size > 1)
        chunks.forEach { assertTrue("块长度 ${it.length}", it.length <= 200) }
    }

    @Test
    fun `超长段落硬切且尽量在句号断开`() {
        val text = "这是一个很长的句子。".repeat(100)
        val chunks = TextChunker.chunk(text, maxChars = 60, overlapChars = 0)
        assertTrue(chunks.size > 1)
        chunks.forEach { assertTrue(it.length <= 60) }
        assertTrue(chunks.dropLast(1).all { it.endsWith("。") })
    }

    @Test
    fun `重叠内容保留上下文`() {
        val text = (1..10).joinToString("\n\n") { "段落$it：" + "x".repeat(50) }
        val chunks = TextChunker.chunk(text, maxChars = 120, overlapChars = 40)
        assertTrue(chunks.size > 1)
        assertTrue(chunks[1].startsWith(chunks[0].takeLast(40).take(10)))
    }
}
