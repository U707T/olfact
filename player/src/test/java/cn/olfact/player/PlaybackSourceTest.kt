package cn.olfact.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSourceTest {
    @Test fun `HLS keeps its media type even when CDN declares octet stream`() {
        val source = PlaybackSource("https://example.com/playlist.m3u8", mimeType = "application/octet-stream")
        assertEquals(MediaKind.HLS.mimeType, source.resolvedMimeType())
    }
    @Test fun `DASH keeps its media type even when CDN declares generic XML`() {
        val source = PlaybackSource("https://example.com/manifest.mpd", mimeType = "application/xml")
        assertEquals(MediaKind.DASH.mimeType, source.resolvedMimeType())
    }
    @Test fun `progressive media keeps the provided MIME`() {
        val source = PlaybackSource("https://example.com/audio.mp3", mimeType = "audio/mpeg")
        assertEquals("audio/mpeg", source.resolvedMimeType())
    }
}
