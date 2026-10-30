package cn.olfact.ai.net

import cn.olfact.ai.AiProvider
import cn.olfact.ai.ChatDelta
import cn.olfact.ai.ChatMessage
import cn.olfact.ai.ChatParams
import cn.olfact.ai.TokenUsage
import cn.olfact.ai.error.AiError
import cn.olfact.ai.error.AiErrorMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容的流式客户端。
 * - 流式：SSE 逐帧回调，可随时取消（连接会真的断）；
 * - 失败重试：只在"还没吐出任何字节"时重试（避免重复文本）；
 * - token：优先用服务端 usage，没有就估算。
 */
class ChatClient(
    private val client: OkHttpClient = defaultClient(),
) {

    fun stream(
        provider: AiProvider,
        apiKey: String?,
        messages: List<ChatMessage>,
        params: ChatParams = ChatParams(
            temperature = provider.temperature,
            maxTokens = provider.maxTokens,
            topP = provider.topP,
        ),
    ): Flow<ChatDelta> = flow {
        val key = apiKey?.takeIf { it.isNotBlank() }
            ?: throw AiError.ProviderNotConfigured("供应商「${provider.name}」还没填 API Key")

        var attempt = 0
        while (true) {
            var emitted = false
            try {
                collectStream(provider, key, messages, params) { delta ->
                    if (delta.text.isNotEmpty()) emitted = true
                    emit(delta)
                }
                return@flow
            } catch (t: Throwable) {
                val error = AiErrorMapper.fromThrowable(t)
                if (emitted || attempt >= MAX_RETRIES || !AiErrorMapper.isRetryable(error)) throw error
                attempt++
                delay(RETRY_BASE_MS * attempt)
            }
        }
    }.flowOn(Dispatchers.IO)

    /** 一次性返回（用于分块摘要这种不需要流式的中间步骤）。 */
    suspend fun complete(
        provider: AiProvider,
        apiKey: String?,
        messages: List<ChatMessage>,
        params: ChatParams = ChatParams(
            temperature = provider.temperature,
            maxTokens = provider.maxTokens,
            topP = provider.topP,
            stream = false,
        ),
    ): String = withContext(Dispatchers.IO) {
        val key = apiKey?.takeIf { it.isNotBlank() }
            ?: throw AiError.ProviderNotConfigured("供应商「${provider.name}」还没填 API Key")
        val request = buildRequest(provider, key, OpenAiWire.requestBody(provider, messages, params, stream = false))
        client.newCall(request).await().use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful) throw AiErrorMapper.fromHttp(response.code, body)
            OpenAiWire.parseCompletion(body.orEmpty())
                ?: throw AiError.Unknown("服务端返回了空内容")
        }
    }

    private suspend fun collectStream(
        provider: AiProvider,
        apiKey: String,
        messages: List<ChatMessage>,
        params: ChatParams,
        onDelta: suspend (ChatDelta) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val request = buildRequest(provider, apiKey, OpenAiWire.requestBody(provider, messages, params, stream = true))
        val call = client.newCall(request)
        try {
            call.await().use { response ->
                if (!response.isSuccessful) {
                    throw AiErrorMapper.fromHttp(response.code, response.body?.string())
                }
                val source = response.body?.source() ?: throw AiError.Network("响应体为空")
                val framer = SseFramer()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    val event = framer.feedLine(line) ?: continue
                    val data = event.data.trim()
                    if (data.isEmpty()) continue
                    if (data == "[DONE]") {
                        onDelta(ChatDelta(done = true))
                        break
                    }
                    val delta = OpenAiWire.parseChunk(data) ?: continue
                    onDelta(delta)
                    if (delta.done) break
                }
                framer.end()?.let { trailing ->
                    if (trailing.data.isNotBlank() && trailing.data != "[DONE]") {
                        OpenAiWire.parseChunk(trailing.data)?.let { onDelta(it) }
                    }
                }
            }
        } catch (t: Throwable) {
            if (t is AiError) throw t
            throw AiErrorMapper.fromThrowable(t)
        }
    }

    private fun buildRequest(provider: AiProvider, apiKey: String, body: String): Request {
        val builder = Request.Builder()
            .url(provider.endpoint())
            .header("Authorization", "Bearer $apiKey")
            .post(body.toRequestBody(JSON))
        provider.extraHeaders.forEach { (key, value) -> builder.header(key, value) }
        return builder.build()
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val MAX_RETRIES = 2
        private const val RETRY_BASE_MS = 900L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()

        /** 从 delta 流里累计 usage（最后一个 chunk 才带）。 */
        fun mergeUsage(current: TokenUsage?, incoming: TokenUsage?): TokenUsage? = incoming ?: current
    }
}
