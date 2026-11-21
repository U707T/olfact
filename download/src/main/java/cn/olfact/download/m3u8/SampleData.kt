package cn.olfact.download.m3u8

import java.nio.ByteBuffer

/** TrackOutput may already contain bytes belonging to the next sample. */
internal fun consumeSample(
    buffer: ByteArray,
    pending: Int,
    size: Int,
    offset: Int,
    consume: (ByteBuffer) -> Unit,
): Int {
    require(size >= 0 && offset >= 0 && offset <= pending && size <= pending - offset) { "无效的媒体样本边界" }
    if (size > 0) consume(ByteBuffer.wrap(buffer, pending - offset - size, size))
    buffer.copyInto(buffer, 0, pending - offset, pending)
    return offset
}
