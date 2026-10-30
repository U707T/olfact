package cn.olfact.download.queue

import cn.olfact.download.DownloadEngine
import cn.olfact.download.DownloadKind
import cn.olfact.download.DownloadRecord
import cn.olfact.download.DownloadSpec
import cn.olfact.download.DownloadStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 队列持久化用的 JSON 编解码。手写而不是 @Serializable，
 * 因为 download 模块不想为了 6 个字段引入序列化插件（插件属于 B1 的构建权）。
 * 格式向后兼容：缺字段一律取默认值，老版本队列文件不会让应用崩。
 */
object DownloadRecordCodec {

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(record: DownloadRecord): String = encodeOne(record).toString()

    fun encodeAll(records: List<DownloadRecord>): String =
        buildJsonArray { records.forEach { add(encodeOne(it)) } }.toString()

    fun decode(text: String): DownloadRecord? = runCatching {
        decodeOne(json.parseToJsonElement(text).jsonObject)
    }.getOrNull()

    fun decodeAll(text: String): List<DownloadRecord> = runCatching {
        json.parseToJsonElement(text).jsonArray.mapNotNull { element ->
            runCatching { decodeOne(element.jsonObject) }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun encodeOne(record: DownloadRecord): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(record.id))
        put("engine", JsonPrimitive(record.engine.wireName))
        put("status", JsonPrimitive(record.status.wireName))
        put("bytesDownloaded", JsonPrimitive(record.bytesDownloaded))
        put("totalBytes", JsonPrimitive(record.totalBytes))
        put("segmentsDone", JsonPrimitive(record.segmentsDone))
        put("segmentsTotal", JsonPrimitive(record.segmentsTotal))
        record.error?.let { put("error", JsonPrimitive(it)) }
        record.filePath?.let { put("filePath", JsonPrimitive(it)) }
        record.systemDownloadId?.let { put("systemDownloadId", JsonPrimitive(it)) }
        put("createdAt", JsonPrimitive(record.createdAt))
        put("updatedAt", JsonPrimitive(record.updatedAt))
        put("spec", encodeSpec(record.spec))
    }

    private fun encodeSpec(spec: DownloadSpec): JsonObject = buildJsonObject {
        put("url", JsonPrimitive(spec.url))
        put("kind", JsonPrimitive(spec.kind.wireName))
        put("fileName", JsonPrimitive(spec.fileName))
        put("subDir", JsonPrimitive(spec.subDir))
        spec.mimeType?.let { put("mimeType", JsonPrimitive(it)) }
        spec.userAgent?.let { put("userAgent", JsonPrimitive(it)) }
        spec.referer?.let { put("referer", JsonPrimitive(it)) }
        spec.pageUrl?.let { put("pageUrl", JsonPrimitive(it)) }
        spec.pageTitle?.let { put("pageTitle", JsonPrimitive(it)) }
        put("sizeHint", JsonPrimitive(spec.sizeHint))
        put("threadCount", JsonPrimitive(spec.threadCount))
        put("chunkSizeBytes", JsonPrimitive(spec.chunkSizeBytes))
        put("allowDuplicate", JsonPrimitive(spec.allowDuplicate))
        put("headers", buildJsonObject {
            spec.headers.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
        })
    }

    private fun decodeOne(obj: JsonObject): DownloadRecord {
        val spec = decodeSpec(obj["spec"]?.jsonObject ?: JsonObject(emptyMap()))
        return DownloadRecord(
            id = obj.str("id") ?: spec.newId(),
            spec = spec,
            engine = DownloadEngine.fromWire(obj.str("engine")),
            status = DownloadStatus.fromWire(obj.str("status")),
            bytesDownloaded = obj.long("bytesDownloaded") ?: 0L,
            totalBytes = obj.long("totalBytes") ?: -1L,
            segmentsDone = (obj.long("segmentsDone") ?: 0L).toInt(),
            segmentsTotal = (obj.long("segmentsTotal") ?: 0L).toInt(),
            error = obj.str("error"),
            filePath = obj.str("filePath"),
            systemDownloadId = obj.long("systemDownloadId"),
            createdAt = obj.long("createdAt") ?: 0L,
            updatedAt = obj.long("updatedAt") ?: 0L,
        )
    }

    private fun decodeSpec(obj: JsonObject): DownloadSpec {
        val headers = obj["headers"]?.jsonObject?.entries?.associate { (key, value) ->
            key to value.jsonPrimitive.content
        }.orEmpty()
        return DownloadSpec(
            url = obj.str("url").orEmpty(),
            kind = DownloadKind.fromWire(obj.str("kind")),
            fileName = obj.str("fileName").orEmpty(),
            subDir = obj.str("subDir").orEmpty(),
            mimeType = obj.str("mimeType"),
            headers = headers,
            userAgent = obj.str("userAgent"),
            referer = obj.str("referer"),
            pageUrl = obj.str("pageUrl"),
            pageTitle = obj.str("pageTitle"),
            sizeHint = obj.long("sizeHint") ?: -1L,
            threadCount = (obj.long("threadCount") ?: 0L).toInt(),
            chunkSizeBytes = obj.long("chunkSizeBytes") ?: 0L,
            allowDuplicate = obj.bool("allowDuplicate") ?: false,
        )
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull
}
