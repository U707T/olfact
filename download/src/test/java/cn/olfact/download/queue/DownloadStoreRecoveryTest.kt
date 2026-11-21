package cn.olfact.download.queue

import cn.olfact.download.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadStoreRecoveryTest {
    @get:Rule val temp = TemporaryFolder()
    private fun record(id: String) = DownloadRecord(id,
        DownloadSpec("https://example.com/$id", fileName = id), DownloadEngine.CHUNKED)

    @Test fun `interrupted replace restores the previous queue from backup`() {
        val file = File(temp.root, "queue.json")
        File(temp.root, "queue.json.bak").writeText(DownloadRecordCodec.encodeAll(listOf(record("saved"))))
        File(temp.root, "queue.json.tmp").writeText("[partial")
        val store = JsonDownloadStore(file)
        assertEquals(listOf("saved"), store.all().map { it.id })
        store.upsert(record("next"))
        assertEquals(setOf("saved", "next"), JsonDownloadStore(file).all().map { it.id }.toSet())
    }

    @Test fun `failed persistence preserves disk and in-memory state and is reported`() {
        val file = File(temp.root, "queue.json")
        val store = JsonDownloadStore(file)
        store.upsert(record("saved"))
        val original = file.readText()
        val revision = store.currentRevision
        File(temp.root, "queue.json.tmp").mkdir()
        val failure = runCatching { store.upsert(record("lost")) }
        assertTrue(failure.isFailure)
        assertEquals(original, file.readText())
        assertEquals(revision, store.currentRevision)
        assertEquals(listOf("saved"), store.all().map { it.id })
    }

    @Test fun `corrupted queue cannot silently be replaced with an empty list`() {
        val file = temp.newFile("queue.json").apply { writeText("[broken") }
        assertTrue(runCatching { JsonDownloadStore(file).upsert(record("new")) }.isFailure)
        assertEquals("[broken", file.readText())
    }
}
