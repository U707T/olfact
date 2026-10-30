package cn.olfact.download

import cn.olfact.download.m3u8.AdBreakFilter
import cn.olfact.download.m3u8.AdFilterConfig
import cn.olfact.download.m3u8.M3u8Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdBreakFilterTest {

    private fun segmentsOf(body: String): List<cn.olfact.download.m3u8.M3u8Segment> =
        M3u8Parser.parse(
            "#EXTM3U\n#EXT-X-TARGETDURATION:6\n$body",
            "https://cdn.example.com/v/index.m3u8",
        ).segments

    @Test
    fun `丢弃 cue-out 区间并保留其余分片`() {
        val segments = segmentsOf(
            """
            #EXTINF:6.0,
            a.ts
            #EXT-X-CUE-OUT:12
            #EXTINF:6.0,
            ad1.ts
            #EXTINF:6.0,
            ad2.ts
            #EXT-X-CUE-IN
            #EXTINF:6.0,
            b.ts
            """.trimIndent()
        )
        val result = AdBreakFilter.filter(segments)
        assertEquals(2, result.dropped)
        assertEquals(listOf("a.ts", "b.ts"), result.segments.map { it.uri.substringAfterLast('/') })
    }

    @Test
    fun `丢弃 URL 带广告标记的分片`() {
        val segments = segmentsOf(
            """
            #EXTINF:6.0,
            ok.ts
            #EXTINF:6.0,
            https://ads.example.com/preroll/x.ts
            #EXTINF:6.0,
            ok2.ts
            """.trimIndent()
        )
        val result = AdBreakFilter.filter(segments)
        assertEquals(1, result.dropped)
        assertEquals(2, result.segments.size)
    }

    @Test
    fun `全部命中时回退完整列表`() {
        val segments = segmentsOf(
            """
            #EXTINF:6.0,
            https://cdn.example.com/ads/a.ts
            #EXTINF:6.0,
            https://cdn.example.com/ads/b.ts
            """.trimIndent()
        )
        val result = AdBreakFilter.filter(segments)
        assertTrue(result.fallbackToAll)
        assertEquals(2, result.segments.size)
        assertEquals(0, result.dropped)
    }

    @Test
    fun `删除广告后拼接点标记 discontinuity`() {
        val segments = segmentsOf(
            """
            #EXTINF:6.0,
            a.ts
            #EXT-X-CUE-OUT:6
            #EXTINF:6.0,
            ad.ts
            #EXT-X-CUE-IN
            #EXTINF:6.0,
            b.ts
            #EXTINF:6.0,
            c.ts
            """.trimIndent()
        )
        val result = AdBreakFilter.filter(segments)
        assertTrue(result.segments[1].discontinuity)
        assertFalse(result.segments[2].discontinuity)
    }

    @Test
    fun `关闭过滤时原样返回`() {
        val segments = segmentsOf(
            """
            #EXTINF:6.0,
            https://cdn.example.com/ads/a.ts
            #EXTINF:6.0,
            b.ts
            """.trimIndent()
        )
        val result = AdBreakFilter.filter(segments, AdFilterConfig(enabled = false))
        assertEquals(2, result.segments.size)
        assertEquals(0, result.dropped)
    }
}
