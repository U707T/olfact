package cn.olfact.ai.net

import cn.olfact.ai.AiProvider
import cn.olfact.ai.ChatDelta
import cn.olfact.ai.ChatMessage
import cn.olfact.ai.ChatParams
import cn.olfact.ai.TokenUsage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** OpenAI 兼容协议的编解码，纯函数，单测覆盖。 */
object OpenAiWire {

    fun requestBody(
        provider: AiProvider,
        messages: List<ChatMessage>,
        params: ChatParams,
        stream: Boolean = params.stream,
    ): String = buildJsonObject {
        put("model", provider.model)
        put("temperature", if (params.temperature > 0) params.temperature else provider.temperature)
        put("top_p", if (params.topP > 0) params.topP else provider.topP)
        put("max_tokens", if (params.maxTokens > 0) params.maxTokens else provider.maxTokens)
        put("stream", stream)
        if (stream && params.includeUsage) {
            put("stream_options", buildJsonObject { put("include_usage", true) })
        }
        if (params.stop.isNotEmpty()) {
            put("stop", buildJsonArray { params.stop.forEach { add(it) } })
        }
        put("messages", buildJsonArray {
            messages.forEach { message ->
                add(buildJsonObject {
                    put("role", message.role.wireName)
                    put("content", message.content)
                })
            }
        })
    }.toString()

    fun parseChunk(json: String): ChatDelta? {
        val root = parseObject(json) ?: return null
        val choice = firstChoice(root)
        val delta = choice?.get("delta") as? JsonObject
        val text = (delta?.get("content") as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val finish = (choice?.get("finish_reason") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val usage = (root["usage"] as? JsonObject)?.let(::parseUsage)
        val model = (root["model"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (text.isEmpty() && finish == null && usage == null) return null
        return ChatDelta(text = text, finishReason = finish, usage = usage, model = model)
    }

    internal fun parseError(json: String): String? {
        val error = parseObject(json)?.get("error") ?: return null
        if (error == kotlinx.serialization.json.JsonNull) return null
        val message = (error as? JsonObject)?.get("message") ?: error
        return (message as? JsonPrimitive)?.takeIf { it.isString }?.content?.take(200)
            ?.takeIf { it.isNotBlank() } ?: "供应商返回错误，未提供详情"
    }

    private fun parseObject(json: String): JsonObject? =
        runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()

    private fun firstChoice(root: JsonObject): JsonObject? =
        (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject

    fun parseUsage(obj: JsonObject): TokenUsage = TokenUsage(
        promptTokens = (obj["prompt_tokens"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0,
        completionTokens = (obj["completion_tokens"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0,
        totalTokens = (obj["total_tokens"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0,
    )

    /** 非流式响应：`choices[0].message.content`。 */
    fun parseCompletion(json: String): String? {
        val choice = parseObject(json)?.let(::firstChoice)
        val message = choice?.get("message") as? JsonObject
        return (message?.get("content") as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    fun parseCompletionFinishReason(json: String): String? =
        (parseObject(json)?.let(::firstChoice)?.get("finish_reason") as? JsonPrimitive)
            ?.takeIf { it.isString }?.content

    fun parseUsageOf(json: String): TokenUsage? =
        (parseObject(json)?.get("usage") as? JsonObject)?.let(::parseUsage)

    /** 粗略 token 估算：中文按 1.5 字/token，英文按 4 字符/token。 */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var other = 0
        text.forEach { c -> if (c.code in 0x4E00..0x9FFF) cjk++ else other++ }
        return cjk * 2 / 3 + other / 4 + 1
    }
}
