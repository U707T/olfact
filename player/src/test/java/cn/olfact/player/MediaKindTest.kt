package cn.olfact.player

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaKindTest {

    @Test
    fun `m3u8 扩展名优先于 octet-stream`() {
        assertEquals(
            MediaKind.HLS,
            MediaKind.detect("https://cdn.example.com/live/index.m3u8?token=1", "application/octet-stream"),
        )
    }

    @Test
    fun `mpd 识别为 dash`() {
        assertEquals(MediaKind.DASH, MediaKind.detect("https://a.com/v/manifest.mpd"))
    }

    @Test
    fun `常见视频扩展名识别为 progressive`() {
        listOf("a.mp4", "b.webm", "c.mkv", "d.mov", "e.ts", "f.m4a").forEach {
            assertEquals(it, MediaKind.PROGRESSIVE, MediaKind.detect("https://x.com/$it"))
        }
    }

    @Test
    fun `mime 兜底识别 hls`() {
        assertEquals(MediaKind.HLS, MediaKind.detect("https://x.com/play", "application/vnd.apple.mpegurl"))
        assertEquals(MediaKind.HLS, MediaKind.detect("https://x.com/play", "application/x-mpegURL; charset=utf-8"))
    }

    @Test
    fun `未知地址返回 unknown`() {
        assertEquals(MediaKind.UNKNOWN, MediaKind.detect("https://x.com/whatever", null))
        assertEquals(MediaKind.UNKNOWN, MediaKind.detect(null, null))
    }

    @Test
    fun `wire 名称互转大小写不敏感`() {
        assertEquals(MediaKind.HLS, MediaKind.fromWire("HLS"))
        assertEquals(MediaKind.PROGRESSIVE, MediaKind.fromWire("progressive"))
        assertEquals(MediaKind.UNKNOWN, MediaKind.fromWire("nope"))
    }

    @Test
    fun `外部播放器 mime 按类型给具体值`() {
        assertEquals("application/x-mpegurl", MediaKind.externalMime(MediaKind.HLS, null))
        assertEquals("video/*", MediaKind.externalMime(MediaKind.PROGRESSIVE, null))
        assertEquals("video/mp4", MediaKind.externalMime(MediaKind.PROGRESSIVE, "video/mp4"))
    }
}
