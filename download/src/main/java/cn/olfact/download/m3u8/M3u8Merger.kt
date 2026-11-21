package cn.olfact.download.m3u8

import cn.olfact.download.ChunkMerger
import cn.olfact.download.util.Files
import java.io.File
import java.util.concurrent.CancellationException

data class MergeOutcome(val file: File, val remuxed: Boolean, val message: String? = null)

/** Tests can inject a merger without Android's media runtime. */
fun interface SegmentMerger {
    fun merge(segments: List<File>, outputBase: File, extraInitSegment: File?): MergeOutcome
}

/** Remux to MP4 when possible, otherwise keep a complete concatenated stream. */
class M3u8Merger(
    private val checkActive: () -> Unit = {},
    private val remuxer: (List<File>, File) -> File = { segments, output -> TsRemuxer.remux(segments, output, checkActive) },
) : SegmentMerger {
    override fun merge(segments: List<File>, outputBase: File, extraInitSegment: File?): MergeOutcome {
        require(segments.isNotEmpty() && segments.all { it.isFile && it.length() > 0 }) { "分片缺失或为空" }
        require(extraInitSegment == null || (extraInitSegment.isFile && extraInitSegment.length() > 0)) { "初始化分片缺失" }
        val inputs = listOfNotNull(extraInitSegment) + segments
        outputBase.parentFile?.mkdirs()
        val staged = File.createTempFile("hls-", ".tmp", outputBase.parentFile)
        try {
            checkActive()
            val result = try {
                remuxer(inputs, staged)
                checkActive()
                if (staged.length() > 0) {
                    return MergeOutcome(Files.publish(staged, File(outputBase.parentFile, outputBase.name + ".mp4"), checkActive), true)
                }
                "remux 输出为空"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: "remux 失败"
            }
            checkActive()
            ChunkMerger.merge(inputs, staged, checkActive = checkActive)
            checkActive()
            val extension = if (extraInitSegment != null) "mp4" else "ts"
            val file = Files.publish(staged, File(outputBase.parentFile, outputBase.name + ".$extension"), checkActive)
            return MergeOutcome(file, false, "已降级为拼接 ${if (extraInitSegment != null) "fMP4" else "TS"}：$result")
        } finally {
            staged.delete()
        }
    }
}
