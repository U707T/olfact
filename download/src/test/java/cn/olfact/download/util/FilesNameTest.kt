package cn.olfact.download.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FilesNameTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `已有扩展名不再重复追加`() {
        assertEquals("video.mp4", Files.ensureExtension("video.mp4", "mp4"))
        assertEquals("video.MP4", Files.ensureExtension("video.MP4", "mp4"))
    }

    @Test
    fun `没有扩展名时补上`() {
        assertEquals("video.mp4", Files.ensureExtension("video", "mp4"))
    }

    @Test
    fun `扩展名为空时原样返回`() {
        assertEquals("video", Files.ensureExtension("video", ""))
    }

    @Test
    fun `点号在开头或结尾不当作扩展名`() {
        assertEquals(".hidden.mp4", Files.ensureExtension(".hidden", "mp4"))
        assertEquals("video..mp4", Files.ensureExtension("video.", "mp4"))
    }

    @Test
    fun `重名文件加序号且不重复扩展名`() {
        val dir = tmp.newFolder("dl")
        dir.resolve("video.mp4").writeText("a")
        val next = Files.uniqueFile(dir, "video.mp4", "mp4")
        assertEquals("video(1).mp4", next.name)
    }

    @Test
    fun `重名且用户没写扩展名时序号加在扩展名前`() {
        val dir = tmp.newFolder("dl2")
        dir.resolve("video.mp4").writeText("a")
        val next = Files.uniqueFile(dir, "video", "mp4")
        assertEquals("video(1).mp4", next.name)
    }

    @Test
    fun `没有扩展名的重名文件也能加序号`() {
        val dir = tmp.newFolder("dl3")
        dir.resolve("README").writeText("a")
        val next = Files.uniqueFile(dir, "README", "")
        assertEquals("README(1)", next.name)
        assertTrue(next.parentFile == dir)
    }
}
