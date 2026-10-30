package cn.olfact.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkPlannerTest {

    @Test
    fun `分块严丝合缝覆盖整个文件`() {
        val total = 10_000_000L
        val ranges = ChunkPlanner.plan(total, 4_000_000L)
        assertEquals(total, ranges.sumOf { it.length })
        assertEquals(0L, ranges.first().start)
        assertEquals(total - 1, ranges.last().endInclusive)
        ranges.zipWithNext().forEach { (a, b) ->
            assertEquals(a.endInclusive + 1, b.start)
        }
    }

    @Test
    fun `小于一个块时只有一块`() {
        val ranges = ChunkPlanner.plan(1234L, 4_000_000L)
        assertEquals(1, ranges.size)
        assertEquals(ByteRange(0, 0, 1233), ranges.first())
    }

    @Test
    fun `长度未知返回空计划`() {
        assertTrue(ChunkPlanner.plan(-1L, 4_000_000L).isEmpty())
        assertTrue(ChunkPlanner.plan(0L, 4_000_000L).isEmpty())
    }

    @Test
    fun `块数过多时自动放大块大小`() {
        val total = 50L * 1024 * 1024 * 1024 // 50G
        val ranges = ChunkPlanner.plan(total, 4L * 1024 * 1024)
        assertTrue("块数 ${ranges.size} 应该被限制", ranges.size <= 4000)
        assertEquals(total, ranges.sumOf { it.length })
    }

    @Test
    fun `恢复偏移不会越过块尾`() {
        val range = ByteRange(1, 100, 199)
        assertEquals(150L, ChunkPlanner.resumeOffset(range, 50))
        assertEquals(200L, ChunkPlanner.resumeOffset(range, 500))
        assertEquals(100L, ChunkPlanner.resumeOffset(range, -3))
    }
}
