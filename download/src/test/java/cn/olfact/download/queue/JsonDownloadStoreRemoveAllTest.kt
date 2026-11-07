package cn.olfact.download.queue

import cn.olfact.download.DownloadEngine
import cn.olfact.download.DownloadRecord
import cn.olfact.download.DownloadSpec
import cn.olfact.download.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 「清空已下载」的批量删除：一次落盘、记录全没。 */
class JsonDownloadStoreRemoveAllTest {

    private fun record(id: String) = DownloadRecord(
        id = id,
        spec = DownloadSpec(url = "https://example.com/$id.bin", fileName = "$id.bin"),
        engine = DownloadEngine.CHUNKED,
        status = DownloadStatus.COMPLETED,
    )

    @Test
    fun `批量删除把多条记录一起删掉`() {
        val dir = Files.createTempDirectory("olfact-queue-removeall").toFile()
        try {
            val store = JsonDownloadStore(File(dir, "queue.json"))
            store.upsert(record("a"))
            store.upsert(record("b"))
            store.upsert(record("c"))

            store.removeAll(listOf("a", "c"))

            assertEquals(listOf("b"), store.all().map { it.id })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `批量删除不存在的 id 不会写坏队列`() {
        val dir = Files.createTempDirectory("olfact-queue-removeall2").toFile()
        try {
            val store = JsonDownloadStore(File(dir, "queue.json"))
            store.upsert(record("keep"))

            store.removeAll(listOf("nope", "missing"))

            assertEquals(listOf("keep"), store.all().map { it.id })
        } finally {
            dir.deleteRecursively()
        }
    }
}
