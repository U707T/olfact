package cn.olfact.ai

import cn.olfact.ai.crypto.SecretBox
import cn.olfact.core.b4.writeAtomicTextFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * 供应商列表 + 当前选择，持久化到 JSON 文件；apiKey 单独进 [SecretBox]。
 * 文件损坏时回退到预置列表，不会让设置页白屏。
 */
class ProviderStore(
    private val file: File,
    private val secrets: SecretBox,
) {

    private val lock = Any()

    fun list(): List<AiProvider> = synchronized(lock) { load().providers }

    fun get(id: String): AiProvider? = list().firstOrNull { it.id == id }

    fun selected(): AiProvider? {
        val state = synchronized(lock) { load() }
        return state.providers.firstOrNull { it.id == state.selectedId && it.enabled }
            ?: state.providers.firstOrNull { it.enabled }
    }

    fun selectedId(): String? = synchronized(lock) { load().selectedId }

    fun select(id: String) {
        synchronized(lock) {
            val state = load()
            persist(state.copy(selectedId = id))
        }
    }

    fun upsert(provider: AiProvider, apiKey: String? = null) {
        synchronized(lock) {
            val state = load()
            val updated = state.providers.toMutableList()
            val index = updated.indexOfFirst { it.id == provider.id }
            if (index >= 0) updated[index] = provider else updated.add(provider)
            persist(state.copy(providers = updated, selectedId = state.selectedId ?: provider.id))
            if (apiKey != null) {
                try {
                    if (apiKey.isBlank()) secrets.remove(provider.keyRef) else secrets.put(provider.keyRef, apiKey)
                } catch (e: Exception) {
                    runCatching { persist(state) }.onFailure { e.addSuppressed(it) }
                    throw e
                }
            }
        }
    }

    fun remove(id: String) {
        synchronized(lock) {
            val state = load()
            val provider = state.providers.firstOrNull { it.id == id }
            val remaining = state.providers.filterNot { it.id == id }
            val selected = if (state.selectedId == id) remaining.firstOrNull()?.id else state.selectedId
            persist(state.copy(providers = remaining, selectedId = selected))
            provider?.let { secrets.remove(it.keyRef) }
        }
    }

    fun apiKey(provider: AiProvider): String? = secrets.get(provider.keyRef)

    fun setApiKey(provider: AiProvider, key: String?) {
        if (key.isNullOrBlank()) secrets.remove(provider.keyRef) else secrets.put(provider.keyRef, key)
    }

    fun hasApiKey(provider: AiProvider): Boolean = !apiKey(provider).isNullOrBlank()

    private fun load(): State {
        if (!file.exists() || file.length() == 0L) return State(AiProvider.presets(), null)
        val text = runCatching { file.readText() }.getOrNull() ?: return State(AiProvider.presets(), null)
        return runCatching { decode(text) }.getOrElse { State(AiProvider.presets(), null) }
    }

    private fun persist(state: State) = writeAtomicTextFile(file, encode(state))

    private fun encode(state: State): String = buildJsonObject {
        put("selectedId", JsonPrimitive(state.selectedId))
        put("providers", buildJsonArray {
            state.providers.forEach { provider ->
                add(buildJsonObject {
                    put("id", JsonPrimitive(provider.id))
                    put("name", JsonPrimitive(provider.name))
                    put("baseUrl", JsonPrimitive(provider.baseUrl))
                    put("model", JsonPrimitive(provider.model))
                    put("keyRef", JsonPrimitive(provider.keyRef))
                    put("temperature", JsonPrimitive(provider.temperature))
                    put("maxTokens", JsonPrimitive(provider.maxTokens))
                    put("topP", JsonPrimitive(provider.topP))
                    put("enabled", JsonPrimitive(provider.enabled))
                    provider.systemPrompt?.let { put("systemPrompt", JsonPrimitive(it)) }
                    put("extraHeaders", buildJsonObject {
                        provider.extraHeaders.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                    })
                })
            }
        })
    }.toString()

    private fun decode(text: String): State {
        val root = Json.parseToJsonElement(text).jsonObject
        val providers = root["providers"]?.jsonArray?.map { element -> decodeProvider(element.jsonObject) }
        val selected = (root["selectedId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return State(providers ?: AiProvider.presets(), selected)
    }

    private fun decodeProvider(obj: JsonObject): AiProvider {
        fun str(key: String): String? = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return AiProvider(
            id = str("id").orEmpty(),
            name = str("name").orEmpty(),
            baseUrl = str("baseUrl").orEmpty(),
            model = str("model").orEmpty(),
            keyRef = str("keyRef") ?: "provider_${str("id").orEmpty()}",
            temperature = (obj["temperature"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.7,
            maxTokens = ((obj["maxTokens"] as? JsonPrimitive)?.longOrNull ?: 2048L).toInt(),
            topP = (obj["topP"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 1.0,
            systemPrompt = str("systemPrompt"),
            extraHeaders = obj["extraHeaders"]?.jsonObject?.entries?.associate { (k, v) -> k to v.jsonPrimitive.content }.orEmpty(),
            enabled = (obj["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
        )
    }

    private data class State(val providers: List<AiProvider>, val selectedId: String?)
}
