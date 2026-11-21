package cn.olfact.download

import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadPathsTest {
    @get:Rule val temp = TemporaryFolder()
    private fun context() = object : ContextWrapper(null) {
        override fun getFilesDir(): File = temp.root
        override fun getExternalFilesDir(type: String?): File = File(temp.root, "downloads")
    }

    @Test fun `nested download directories remain supported`() {
        val target = DownloadPaths.resolveTarget(context(), DownloadSpec("https://example.com/file.bin",
            fileName = "file", subDir = "movies/series"))
        assertEquals(File(temp.root, "downloads/olfact/movies/series/file.bin").canonicalFile, target.canonicalFile)
    }

    @Test fun `simultaneous targets reserve distinct names before system downloads create files`() {
        val context = context()
        val spec = DownloadSpec("https://example.com/file.bin", fileName = "file")
        val first = DownloadPaths.resolveTarget(context, spec)
        val next = DownloadPaths.resolveTarget(context, spec, setOf(first))
        assertEquals("file.bin", first.name)
        assertEquals("file(1).bin", next.name)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parent traversal cannot write outside the download directory`() {
        DownloadPaths.resolveTarget(context(), DownloadSpec("https://example.com/file.bin", fileName = "file", subDir = "../../files"))
    }
}
