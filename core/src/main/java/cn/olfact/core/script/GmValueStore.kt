package cn.olfact.core.script

import cn.olfact.core.b4.B4Log
import cn.olfact.core.b4.StoreFiles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * `GM_setValue` / `GM_getValue` 的落盘实现。
 *
 * 值统一以 **JSON 文本** 存：JS 侧 `JSON.parse` 拿到原类型（数字/布尔/对象/数组都不丢）。
 * 每个脚本一个文件，删脚本即删文件，不会残留。
 */
class GmValueStore(
    private val dir: File,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {

    private val cache = HashMap<String, MutableMap<String, String>>()

    /**
     * 文件名用的 id：与 [ScriptStore] 的 `fileId` 同一规则，只留字母数字与 `-` `_`。
     *
     * scriptId 来自页面上跑的 .user.js（`@name`/`@namespace` 派生的 id），是外部输入。
     * 之前直接 `File(dir, "$scriptId.json")`，id 里带 `../` 就能把 GM 存储写到 gm 目录外面。
     */
    private fun fileId(scriptId: String): String =
        scriptId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "script" }

    private fun fileFor(scriptId: String): File = File(dir, "${fileId(scriptId)}.json")

    @Synchronized
    private fun mapFor(scriptId: String): MutableMap<String, String> = cache.getOrPut(scriptId) {
        val text = StoreFiles.readText(fileFor(scriptId))
        val map = LinkedHashMap<String, String>()
        if (!text.isNullOrBlank()) {
            runCatching {
                json.parseToJsonElement(text).jsonObject.forEach { (k, v) ->
                    map[k] = if (v is JsonPrimitive) v.content else v.toString()
                }
            }.onFailure { B4Log.w("GmValue", "GM 存储损坏：$scriptId", it) }
        }
        map
    }

    /** 返回 JSON 文本（"null" 表示没有值）。 */
    @Synchronized
    fun get(scriptId: String, key: String): String = mapFor(scriptId)[key] ?: "null"

    @Synchronized
    fun set(scriptId: String, key: String, jsonValue: String) {
        mapFor(scriptId)[key] = jsonValue
        flushLocked(scriptId)
    }

    @Synchronized
    fun delete(scriptId: String, key: String) {
        if (mapFor(scriptId).remove(key) != null) flushLocked(scriptId)
    }

    @Synchronized
    fun keys(scriptId: String): List<String> = mapFor(scriptId).keys.toList()

    @Synchronized
    fun clear(scriptId: String) {
        cache.remove(scriptId)
        fileFor(scriptId).delete()
    }

    private fun flushLocked(scriptId: String) {
        val map = cache[scriptId] ?: return
        val obj: JsonObject = buildJsonObject {
            for ((k, v) in map) {
                put(k, JsonPrimitive(v))
            }
        }
        runCatching { StoreFiles.writeAtomic(fileFor(scriptId), obj.toString()) }
            .onFailure { B4Log.w("GmValue", "写入 GM 存储失败：$scriptId", it) }
    }
}
