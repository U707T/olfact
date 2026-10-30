package cn.olfact.ai.net

import cn.olfact.ai.AiProvider
import cn.olfact.ai.ChatDelta
import cn.olfact.ai.ChatMessage
import cn.olfact.ai.ChatParams
import cn.olfact.ai.TokenUsage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
        val choices = root["choices"]?.jsonArray
        if (choices.isNullOrEmpty()) {
            val usage = root["usage"]?.let { parseUsage(it.jsonObject) }
            return if (usage != null) ChatDelta(usage = usage) else null
        }
        val choice = choices.first().jsonObject
        val delta = choice["delta"]?.jsonObject
        val text = (delta?.get("content") as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val finish = (choice["finish_reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val usage = root["usage"]?.let { parseUsage(it.jsonObject) }
        val model = (root["model"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (text.isEmpty() && finish == null && usage == null) return null
        return ChatDelta(
            text = text,
            done = finish != null && text.isEmpty() && usage == null,
            finishReason = finish,
            usage = usage,
            model = model,
        )
    }

    fun parseUsage(obj: JsonObject): TokenUsage = TokenUsage(
        promptTokens = (obj["prompt_tokens"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0,
        completionTokens = (obj["completion_tokens"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0,
        totalTokens = (obj["total_tokens"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0,
    )

    /** 非流式响应：`choices[0].message.content`。 */
    fun parseCompletion(json: String): String? {
        val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
        val message = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
        return (message?.get("content") as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    fun parseUsageOf(json: String): TokenUsage? =
        runCatching { Json.parseToJsonElement(json).jsonObject["usage"]?.jsonObject }
            .getOrNull()?.let { parseUsage(it) }

    /** 粗略 token 估算：中文按 1.5 字/token，英文按 4 字符/token。 */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var other = 0
        text.forEach { c -> if (c.code in 0x4E00..0x9FFF) cjk++ else other++ }
        return cjk * 2 / 3 + other / 4 + 1
    }
}
