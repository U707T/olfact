package cn.olfact.web.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadNamesTest {

    @Test
    fun `引号包裹的 filename`() {
        assertEquals(
            "video.mp4",
            DownloadNames.fromContentDisposition("attachment; filename=\"video.mp4\"")
        )
    }

    @Test
    fun `没有引号的 filename`() {
        assertEquals(
            "video.mp4",
            DownloadNames.fromContentDisposition("attachment; filename=video.mp4")
        )
    }

    @Test
    fun `filename 星号带 UTF-8 编码`() {
        assertEquals(
            "视频.mp4",
            DownloadNames.fromContentDisposition("attachment; filename*=UTF-8''%E8%A7%86%E9%A2%91.mp4")
        )
    }

    @Test
    fun `filename 星号优先于普通 filename`() {
        assertEquals(
            "真名.mp4",
            DownloadNames.fromContentDisposition(
                "attachment; filename=\"fallback.mp4\"; filename*=UTF-8''%E7%9C%9F%E5%90%8D.mp4"
            )
        )
    }

    @Test
    fun `大小写不敏感`() {
        assertEquals(
            "a.zip",
            DownloadNames.fromContentDisposition("Attachment; FileName=\"a.zip\"")
        )
    }

    @Test
    fun `去掉服务端塞进来的路径`() {
        assertEquals(
            "a.mp4",
            DownloadNames.fromContentDisposition("attachment; filename=\"../../etc/a.mp4\"")
        )
    }

    @Test
    fun `没有文件名时返回 null`() {
        assertNull(DownloadNames.fromContentDisposition(null))
        assertNull(DownloadNames.fromContentDisposition(""))
        assertNull(DownloadNames.fromContentDisposition("attachment"))
        assertNull(DownloadNames.fromContentDisposition("attachment; filename=\"\""))
    }

    @Test
    fun `编码坏掉时不崩，退回普通 filename`() {
        assertEquals(
            "ok.mp4",
            DownloadNames.fromContentDisposition(
                "attachment; filename=\"ok.mp4\"; filename*=UTF-8''%ZZ"
            )
        )
    }
}
