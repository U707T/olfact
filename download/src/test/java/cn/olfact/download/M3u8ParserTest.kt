package cn.olfact.download

import cn.olfact.download.m3u8.M3u8Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3u8ParserTest {

    private val mediaPlaylist = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:10
        #EXT-X-MEDIA-SEQUENCE:7
        #EXTINF:9.009,
        seg0.ts
        #EXTINF:9.009,
        https://cdn.example.com/seg1.ts?token=abc
        #EXT-X-DISCONTINUITY
        #EXTINF:3.003,广告时间
        ads/ad2.ts
        #EXT-X-ENDLIST
    """.trimIndent()

    @Test
    fun `解析 media playlist 的基本字段`() {
        val playlist = M3u8Parser.parse(mediaPlaylist, "https://cdn.example.com/path/index.m3u8")
        assertEquals(false, playlist.isMaster)
        assertEquals(3, playlist.segmentCount)
        assertEquals(10, playlist.targetDurationSec)
        assertEquals(7L, playlist.mediaSequence)
        assertTrue(playlist.ended)
        assertEquals(21_021L, playlist.totalDurationMs)
        assertEquals("https://cdn.example.com/path/seg0.ts", playlist.segments[0].uri)
        assertEquals("https://cdn.example.com/seg1.ts?token=abc", playlist.segments[1].uri)
        assertEquals(7L, playlist.segments[0].sequence)
        assertEquals(9L, playlist.segments[2].sequence)
        assertTrue(playlist.segments[2].discontinuity)
        assertEquals("广告时间", playlist.segments[2].title)
    }

    @Test
    fun `master playlist 选最高码率`() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.42e01e,mp4a.40.2"
            360p/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2"
            720p/index.m3u8
        """.trimIndent()
        val playlist = M3u8Parser.parse(master, "https://cdn.example.com/live/master.m3u8")
        assertTrue(playlist.isMaster)
        assertEquals(2, playlist.variants.size)
        assertEquals("https://cdn.example.com/live/720p/index.m3u8", playlist.bestVariant()?.uri)
        assertEquals(2400000L, playlist.bestVariant()?.bandwidth)
        assertEquals(720, playlist.bestVariant()?.height)
        assertEquals("https://cdn.example.com/live/360p/index.m3u8", playlist.variantFor(400)?.uri)
    }

    @Test
    fun `AES-128 密钥与 IV 解析`() {
        val encrypted = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-KEY:METHOD=AES-128,URI="https://keys.example.com/k1",IV=0x000102030405060708090A0B0C0D0E0F
            #EXTINF:6.0,
            a.ts
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:6.0,
            b.ts
        """.trimIndent()
        val playlist = M3u8Parser.parse(encrypted, "https://cdn.example.com/v/index.m3u8")
        assertTrue(playlist.hasEncryption)
        val key = playlist.segments[0].key
        assertNotNull(key)
        assertTrue(key!!.isAes128)
        assertEquals("https://keys.example.com/k1", key.uri)
        assertEquals("0x000102030405060708090A0B0C0D0E0F", key.ivHex)
        assertNull(playlist.segments[1].key?.uri)
        assertEquals(false, playlist.segments[1].key!!.isEncrypted)
    }

    @Test
    fun `CUE-OUT 区间内的分片标记为广告`() {
        val playlist = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.0,
            normal1.ts
            #EXT-X-CUE-OUT:30
            #EXTINF:6.0,
            ad1.ts
            #EXTINF:6.0,
            ad2.ts
            #EXT-X-CUE-IN
            #EXTINF:6.0,
            normal2.ts
        """.trimIndent()
        val parsed = M3u8Parser.parse(playlist, "https://cdn.example.com/v/index.m3u8")
        assertEquals(listOf(false, true, true, false), parsed.segments.map { it.adBreak })
    }

    @Test
    fun `EXT-X-MAP 与 BYTERANGE`() {
        val playlist = """
            #EXTM3U
            #EXT-X-MAP:URI="init.mp4"
            #EXT-X-BYTERANGE:1000@0
            #EXTINF:4.0,
            file.mp4
        """.trimIndent()
        val segment = M3u8Parser.parse(playlist, "https://cdn.example.com/v/index.m3u8").segments.single()
        assertEquals("https://cdn.example.com/v/init.mp4", segment.mapUri)
        assertEquals(1000L, segment.byteRangeLength)
        assertEquals(0L, segment.byteRangeOffset)
    }

    @Test
    fun `属性解析支持引号内逗号`() {
        val attrs = M3u8Parser.parseAttributes(
            """BANDWIDTH=1000,CODECS="avc1.4d401f,mp4a.40.2",RESOLUTION=1280x720"""
        )
        assertEquals("1000", attrs["BANDWIDTH"])
        assertEquals("avc1.4d401f,mp4a.40.2", attrs["CODECS"])
        assertEquals("1280x720", attrs["RESOLUTION"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `非 m3u8 内容直接抛错`() {
        M3u8Parser.parse("<html>404</html>", "https://a.com/x.m3u8")
    }
}
