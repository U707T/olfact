package cn.olfact.download.m3u8

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.muxer.Muxer
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * TS → MP4 的 remux：TsExtractor 解复用 → Mp4Muxer 封装。
 * 不做转码（不引 ffmpeg），所以只对 H.264/H.265 + AAC 这类 MP4 能装的编码有效；
 * 失败由 [M3u8Merger] 降级为"仅拼接 .ts"。
 */
@OptIn(UnstableApi::class)
object TsRemuxer {

    class RemuxException(message: String, cause: Throwable? = null) : Exception(message, cause)

    fun remux(segments: List<File>, output: File): File {
        val files = segments.filter { it.exists() && it.length() > 0 }
        if (files.isEmpty()) throw RemuxException("没有可 remux 的分片")

        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()

        val totalLength = files.sumOf { it.length() }
        val reader = MultiFileDataReader(files)
        val stream = FileOutputStream(output)
        val muxer = Mp4Muxer.Builder(stream).build()
        try {
            val output2 = MuxerExtractorOutput(muxer)
            // 拼接后的 HLS 分片每段都有自己的 PAT/PMT，必须显式用 (mode, flags)。
            // 注意单参构造函数是「payload reader flags」且已废弃：直接传 MODE_HLS
            // 会被当成 FLAG_ALLOW_NON_IDR_KEYFRAMES，模式仍是 MODE_SINGLE_PMT。
            val extractor: Extractor = TsExtractor(
                TsExtractor.MODE_HLS,
                0,
                TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES
            )
            extractor.init(output2)
            val input = DefaultExtractorInput(reader, 0, totalLength)
            val positionHolder = PositionHolder()
            var position = 0L
            var result = Extractor.RESULT_CONTINUE
            while (result != Extractor.RESULT_END_OF_INPUT) {
                result = extractor.read(input, positionHolder)
                if (result == Extractor.RESULT_SEEK) {
                    position = positionHolder.position.coerceAtLeast(0L)
                    throw RemuxException("TS 需要随机 seek（position=$position），降级拼接")
                }
            }
            extractor.release()
            output2.flush()
        } catch (e: RemuxException) {
            runCatching { muxer.close() }
            runCatching { stream.close() }
            output.delete()
            throw e
        } catch (e: Throwable) {
            runCatching { muxer.close() }
            runCatching { stream.close() }
            output.delete()
            throw RemuxException("remux 失败：${e.message}", e)
        } finally {
            runCatching { reader.close() }
        }

        runCatching { muxer.close() }
        runCatching { stream.close() }
        if (!output.exists() || output.length() == 0L) {
            output.delete()
            throw RemuxException("remux 输出为空")
        }
        return output
    }

    /**
     * 把多个文件当成一个连续输入喂给 extractor：
     * HLS 分片的 PTS 是连续的，拼起来才能得到一条正确的时间线。
     */
    private class MultiFileDataReader(private val files: List<File>) : DataReader {
        private var index = 0
        private var current: RandomAccessFile? = null

        override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
            while (index < files.size) {
                val raf = current ?: RandomAccessFile(files[index], "r").also { current = it }
                val read = raf.read(buffer, offset, readLength)
                if (read > 0) return read
                runCatching { raf.close() }
                current = null
                index++
            }
            return C.RESULT_END_OF_INPUT
        }

        fun close() {
            runCatching { current?.close() }
            current = null
        }
    }

    private class MuxerExtractorOutput(private val muxer: Muxer) : ExtractorOutput {
        private val tracks = HashMap<Int, MuxerTrackOutput>()

        override fun track(id: Int, type: Int): TrackOutput =
            tracks.getOrPut(id) { MuxerTrackOutput(muxer) }

        override fun endTracks() = Unit

        override fun seekMap(seekMap: SeekMap) = Unit

        fun flush() {
            tracks.values.forEach { it.flush() }
        }
    }

    private class MuxerTrackOutput(private val muxer: Muxer) : TrackOutput {
        private var trackId = -1
        private var buffer = ByteArray(INITIAL_BUFFER)
        private var pending = 0

        override fun format(format: Format) {
            if (trackId < 0) {
                trackId = muxer.addTrack(format)
            }
        }

        override fun sampleData(
            input: DataReader,
            size: Int,
            loadingFinished: Boolean,
            sampleDataPart: Int,
        ): Int {
            ensureCapacity(pending + size)
            var read = 0
            while (read < size) {
                val count = input.read(buffer, pending + read, size - read)
                if (count == C.RESULT_END_OF_INPUT) break
                read += count
            }
            pending += read
            return read
        }

        override fun sampleData(data: ParsableByteArray, size: Int, sampleDataPart: Int) {
            ensureCapacity(pending + size)
            data.readBytes(buffer, pending, size)
            pending += size
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) {
            if (trackId < 0) {
                pending = 0
                return
            }
            val sampleSize = if (size > 0) minOf(size, pending) else pending
            if (sampleSize <= 0) {
                pending = 0
                return
            }
            val byteBuffer = ByteBuffer.wrap(buffer, 0, sampleSize)
            muxer.writeSampleData(trackId, byteBuffer, BufferInfo(timeUs, sampleSize, flags))
            pending = 0
        }

        fun flush() {
            pending = 0
        }

        private fun ensureCapacity(required: Int) {
            if (buffer.size >= required) return
            var newSize = buffer.size
            while (newSize < required) newSize *= 2
            buffer = buffer.copyOf(newSize)
        }
    }

    private const val INITIAL_BUFFER = 256 * 1024
}
