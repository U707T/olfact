package cn.olfact.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChunkPartNameTest {

    @Test
    fun `普通文件名保持可读`() {
        assertTrue(ChunkDownloader.partName("video.mp4", 3).startsWith("video.mp4.part3"))
    }

    @Test
    fun `路径分隔符被替换`() {
        val name = ChunkDownloader.partName("../../evil", 0)
        assertFalse(name.contains('/'))
        assertFalse(name.contains('\\'))
    }

    @Test
    fun `带穿越的文件名解析后仍在工作目录内`() {
        val root = File("/tmp/olfact-work").canonicalFile
        val part = File(root, ChunkDownloader.partName("../../../files/evil", 7)).canonicalFile
        assertTrue(
            "part 解析到工作目录外：${part.path}",
            part.path.startsWith(root.path + File.separator)
        )
    }

    @Test
    fun `空文件名也有兜底`() {
        assertTrue(ChunkDownloader.partName("", 2).endsWith(".part2"))
    }
}
