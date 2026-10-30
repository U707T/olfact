package cn.olfact.download.m3u8

import java.net.URI

/**
 * 手写 m3u8 解析：够用、可单测、不引第三方库。
 * 支持 master / media、AES-128 密钥、EXT-X-MAP、BYTERANGE、CUE-OUT 广告区间。
 */
object M3u8Parser {

    fun parse(content: String, baseUrl: String): M3u8Playlist {
        val text = content.removePrefix("\uFEFF")
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        require(lines.firstOrNull()?.startsWith("#EXTM3U") == true) { "不是有效的 m3u8 文件" }

        val variants = mutableListOf<M3u8Variant>()
        val segments = mutableListOf<M3u8Segment>()

        var targetDuration = 0
        var mediaSequence = 0L
        var ended = false
        var pendingVariant: M3u8Variant? = null
        var pendingDurationMs = -1L
        var pendingTitle: String? = null
        var pendingDiscontinuity = false
        var pendingByteRangeLength: Long? = null
        var pendingByteRangeOffset: Long? = null
        var currentKey: M3u8Key? = null
        var currentMap: String? = null
        var inAdBreak = false
        var adRemainingMs = 0L
        var sequence = 0L
        var hasEncryption = false

        for (line in lines) {
            when {
                line.startsWith("#EXT-X-STREAM-INF") -> {
                    pendingVariant = parseVariant(line)
                }

                line.startsWith("#EXT-X-TARGETDURATION") -> {
                    targetDuration = attribute(line).toIntOrNull() ?: 0
                }

                line.startsWith("#EXT-X-MEDIA-SEQUENCE") -> {
                    mediaSequence = attribute(line).toLongOrNull() ?: 0L
                    sequence = mediaSequence
                }

                line.startsWith("#EXT-X-ENDLIST") -> ended = true

                line.startsWith("#EXT-X-DISCONTINUITY") && !line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE") -> {
                    pendingDiscontinuity = true
                }

                line.startsWith("#EXT-X-KEY") -> {
                    currentKey = parseKey(line, baseUrl)
                    if (currentKey?.isEncrypted == true) hasEncryption = true
                }

                line.startsWith("#EXT-X-MAP") -> {
                    currentMap = parseAttributes(line.substringAfter(':')).get("URI")
                        ?.let { resolve(baseUrl, it) }
                }

                line.startsWith("#EXT-X-BYTERANGE") -> {
                    val value = attribute(line)
                    val length = value.substringBefore('@').toLongOrNull()
                    val offset = value.substringAfter('@', "").toLongOrNull()
                    pendingByteRangeLength = length
                    pendingByteRangeOffset = offset
                }

                line.startsWith("#EXTINF") -> {
                    val value = attribute(line).substringBefore(',').trim()
                    pendingDurationMs = (value.toDoubleOrNull() ?: 0.0).times(1000).toLong()
                    pendingTitle = attribute(line).substringAfter(',', "").trim().ifEmpty { null }
                }

                // ---- 广告标记 ----
                line.startsWith("#EXT-X-CUE-OUT") && !line.startsWith("#EXT-X-CUE-OUT-CONT") -> {
                    inAdBreak = true
                    adRemainingMs = attribute(line).toDoubleOrNull()?.times(1000)?.toLong() ?: Long.MAX_VALUE
                }

                line.startsWith("#EXT-X-CUE-IN") -> {
                    inAdBreak = false
                    adRemainingMs = 0L
                }

                line.startsWith("#EXT-X-DATERANGE") -> {
                    val attrs = parseAttributes(line.substringAfter(':'))
                    val isAd = attrs["CLASS"]?.contains("ad", ignoreCase = true) == true ||
                        attrs.containsKey("X-AD") || attrs["ID"]?.contains("ad", ignoreCase = true) == true
                    if (isAd) {
                        inAdBreak = true
                        adRemainingMs = attrs["DURATION"]?.toDoubleOrNull()?.times(1000)?.toLong() ?: Long.MAX_VALUE
                    }
                }

                line.startsWith("#EXT-X-AD") -> inAdBreak = true

                line.startsWith("#") -> Unit // 其它标签忽略

                else -> {
                    // 普通行：要么是 variant，要么是分片
                    val variant = pendingVariant
                    if (variant != null) {
                        variants += variant.copy(uri = resolve(baseUrl, line))
                        pendingVariant = null
                    } else {
                        val duration = if (pendingDurationMs >= 0) pendingDurationMs else 0L
                        val ad = inAdBreak
                        segments += M3u8Segment(
                            uri = resolve(baseUrl, line),
                            durationMs = duration,
                            sequence = sequence,
                            discontinuity = pendingDiscontinuity,
                            key = currentKey,
                            mapUri = currentMap,
                            byteRangeLength = pendingByteRangeLength,
                            byteRangeOffset = pendingByteRangeOffset,
                            title = pendingTitle,
                            adBreak = ad,
                        )
                        sequence++
                        pendingDurationMs = -1L
                        pendingTitle = null
                        pendingDiscontinuity = false
                        pendingByteRangeLength = null
                        pendingByteRangeOffset = null
                        if (ad && adRemainingMs != Long.MAX_VALUE) {
                            adRemainingMs -= duration
                            if (adRemainingMs <= 0) {
                                inAdBreak = false
                                adRemainingMs = 0
                            }
                        }
                    }
                }
            }
        }

        val isMaster = variants.isNotEmpty() && segments.isEmpty()
        return M3u8Playlist(
            url = baseUrl,
            isMaster = isMaster,
            variants = variants,
            segments = segments,
            targetDurationSec = targetDuration,
            mediaSequence = mediaSequence,
            ended = ended,
            totalDurationMs = segments.sumOf { it.durationMs },
            hasEncryption = hasEncryption,
        )
    }

    private fun parseVariant(line: String): M3u8Variant {
        val attrs = parseAttributes(line.substringAfter(':', ""))
        return M3u8Variant(
            uri = "",
            bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L,
            resolution = attrs["RESOLUTION"],
            codecs = attrs["CODECS"],
            frameRate = attrs["FRAME-RATE"]?.toDoubleOrNull(),
        )
    }

    private fun parseKey(line: String, baseUrl: String): M3u8Key {
        val attrs = parseAttributes(line.substringAfter(':', ""))
        val method = attrs["METHOD"] ?: "NONE"
        return M3u8Key(
            method = method,
            uri = attrs["URI"]?.let { resolve(baseUrl, it) },
            ivHex = attrs["IV"],
            keyFormat = attrs["KEYFORMAT"],
        )
    }

    private fun attribute(line: String): String = line.substringAfter(':', "").trim()

    /** 解析 `A=1,B="x,y"`；引号内的逗号不切。 */
    fun parseAttributes(raw: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var index = 0
        val length = raw.length
        while (index < length) {
            while (index < length && (raw[index] == ',' || raw[index] == ' ')) index++
            if (index >= length) break
            val keyStart = index
            while (index < length && raw[index] != '=') index++
            if (index >= length) break
            val key = raw.substring(keyStart, index).trim()
            index++ // skip '='
            val value: String
            if (index < length && raw[index] == '"') {
                index++
                val valueStart = index
                while (index < length && raw[index] != '"') index++
                value = raw.substring(valueStart, index)
                if (index < length) index++ // closing quote
            } else {
                val valueStart = index
                while (index < length && raw[index] != ',') index++
                value = raw.substring(valueStart, index).trim()
            }
            if (key.isNotEmpty()) result[key] = value
        }
        return result
    }

    fun resolve(baseUrl: String, reference: String): String {
        if (reference.startsWith("http://") || reference.startsWith("https://")) return reference
        return runCatching { URI(baseUrl).resolve(reference).toString() }.getOrDefault(reference)
    }
}
