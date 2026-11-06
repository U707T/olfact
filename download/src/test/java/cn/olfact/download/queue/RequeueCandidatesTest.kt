package cn.olfact.download.queue

import cn.olfact.download.DownloadEngine
import cn.olfact.download.DownloadRecord
import cn.olfact.download.DownloadSpec
import cn.olfact.download.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `processQueue()` 的「进程重启续传」判定。
 *
 * 背景：WorkManager 与界面 `kick()` 会同时调 `processQueue()`。旧实现把所有 RUNNING
 * 一律改写成 QUEUED，包括本进程正在下的那条 —— UI 随后一直显示「排队中」，
 * 而 `runOne` 又因为 activeIds 已被占用跳过它。
 */
class RequeueCandidatesTest {

    private fun record(
        id: String,
        status: DownloadStatus,
        engine: DownloadEngine = DownloadEngine.CHUNKED,
        systemId: Long? = null,
    ) = DownloadRecord(
        id = id,
        spec = DownloadSpec(url = "https://example.com/$id.bin", fileName = "$id.bin"),
        engine = engine,
        status = status,
        systemDownloadId = systemId,
    )

    @Test
    fun `上次进程留下的 RUNNING 转成 QUEUED`() {
        val out = requeueCandidates(listOf(record("a", DownloadStatus.RUNNING)), emptySet())
        assertEquals(1, out.size)
        assertEquals(DownloadStatus.QUEUED, out.first().status)
        assertEquals("进程重启，自动续传", out.first().error)
    }

    @Test
    fun `本进程正在跑的不动`() {
        val out = requeueCandidates(listOf(record("a", DownloadStatus.RUNNING)), setOf("a"))
        assertTrue(out.isEmpty())
    }

    @Test
    fun `系统下载器任务保留 RUNNING 继续跟踪`() {
        val out = requeueCandidates(
            listOf(record("sys", DownloadStatus.RUNNING, DownloadEngine.SYSTEM, systemId = 42L)),
            emptySet(),
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `系统引擎但丢了 downloadId 的仍要续传`() {
        val out = requeueCandidates(
            listOf(record("sys", DownloadStatus.RUNNING, DownloadEngine.SYSTEM, systemId = null)),
            emptySet(),
        )
        assertEquals(1, out.size)
    }

    @Test
    fun `非 RUNNING 状态一律不碰`() {
        val out = requeueCandidates(
            listOf(
                record("q", DownloadStatus.QUEUED),
                record("p", DownloadStatus.PAUSED),
                record("d", DownloadStatus.COMPLETED),
                record("f", DownloadStatus.FAILED),
                record("c", DownloadStatus.CANCELED),
            ),
            emptySet(),
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `混合场景只动该动的那条`() {
        val out = requeueCandidates(
            listOf(
                record("stale", DownloadStatus.RUNNING),
                record("live", DownloadStatus.RUNNING),
                record("sys", DownloadStatus.RUNNING, DownloadEngine.SYSTEM, systemId = 7L),
            ),
            setOf("live"),
        )
        assertEquals(listOf("stale"), out.map { it.id })
    }
}
