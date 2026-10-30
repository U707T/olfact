package cn.olfact.download

import cn.olfact.download.queue.DownloadRecordCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadRecordCodecTest {

    private val record = DownloadRecord(
        id = "abc",
        spec = DownloadSpec(
            url = "https://cdn.example.com/v.mp4",
            kind = DownloadKind.PROGRESSIVE,
            fileName = "视频 1",
            subDir = "movies",
            mimeType = "video/mp4",
            headers = mapOf("Referer" to "https://example.com", "X-Token" to "t=1"),
            userAgent = "UA/1.0",
            pageUrl = "https://example.com/watch",
            pageTitle = "标题",
            sizeHint = 1024,
            threadCount = 4,
            chunkSizeBytes = 2 * 1024 * 1024,
        ),
        engine = DownloadEngine.CHUNKED,
        status = DownloadStatus.RUNNING,
        bytesDownloaded = 512,
        totalBytes = 1024,
        segmentsDone = 3,
        segmentsTotal = 10,
        error = "重试中",
        filePath = "/tmp/v.mp4",
        systemDownloadId = 42,
        createdAt = 1000,
        updatedAt = 2000,
    )

    @Test
    fun `编解码往返不丢字段`() {
        val decoded = DownloadRecordCodec.decode(DownloadRecordCodec.encode(record))
        assertEquals(record, decoded)
    }

    @Test
    fun `列表编解码`() {
        val text = DownloadRecordCodec.encodeAll(listOf(record, record.copy(id = "def")))
        val decoded = DownloadRecordCodec.decodeAll(text)
        assertEquals(2, decoded.size)
        assertEquals("def", decoded[1].id)
    }

    @Test
    fun `缺字段按默认值恢复`() {
        val minimal = """{"id":"x","spec":{"url":"https://a.com/f.mp4","fileName":"f"}}"""
        val decoded = DownloadRecordCodec.decode(minimal)
        assertEquals("x", decoded?.id)
        assertEquals(DownloadStatus.QUEUED, decoded?.status)
        assertEquals(DownloadEngine.SYSTEM, decoded?.engine)
        assertEquals(DownloadKind.UNKNOWN, decoded?.spec?.kind)
        assertNull(decoded?.error)
    }

    @Test
    fun `坏 JSON 返回 null 而不是崩溃`() {
        assertNull(DownloadRecordCodec.decode("{oops"))
        assertEquals(0, DownloadRecordCodec.decodeAll("not json").size)
    }
}
