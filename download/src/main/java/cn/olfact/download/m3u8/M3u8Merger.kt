package cn.olfact.download.m3u8

import cn.olfact.download.ChunkMerger
import java.io.File

data class MergeOutcome(
    val file: File,
    val remuxed: Boolean,
    val message: String? = null,
)

/** 合并策略接口：单测可以注入假实现，不依赖 media3 的 Android 运行时。 */
fun interface SegmentMerger {
    fun merge(segments: List<File>, outputBase: File, extraInitSegment: File?): MergeOutcome
}

/**
 * 分片合并：优先 remux 成 mp4；任何失败都降级为"仅拼接 .ts"，
 * 保证"下完一定有个能播的文件"。
 */
class M3u8Merger(
    private val remuxer: (List<File>, File) -> File = { segments, output -> TsRemuxer.remux(segments, output) },
) : SegmentMerger {

    override fun merge(
        segments: List<File>,
        outputBase: File,
        extraInitSegment: File?,
    ): MergeOutcome {
        val inputs = buildList {
            extraInitSegment?.takeIf { it.exists() && it.length() > 0 }?.let { add(it) }
            addAll(segments.filter { it.exists() && it.length() > 0 })
        }
        if (inputs.isEmpty()) throw IllegalArgumentException("没有可合并的分片")

        val mp4 = File(outputBase.parentFile, outputBase.nameWithoutExtension + ".mp4")
        val mp4Result = runCatching { remuxer(inputs, mp4) }
        if (mp4Result.isSuccess && mp4.exists() && mp4.length() > 0) {
            return MergeOutcome(mp4, remuxed = true)
        }

        val reason = mp4Result.exceptionOrNull()?.message ?: "remux 输出为空"
        runCatching { if (mp4.exists()) mp4.delete() }

        val ts = File(outputBase.parentFile, outputBase.nameWithoutExtension + ".ts")
        ChunkMerger.merge(inputs, ts, deleteParts = false)
        return MergeOutcome(ts, remuxed = false, message = "已降级为拼接 TS：$reason")
    }
}
