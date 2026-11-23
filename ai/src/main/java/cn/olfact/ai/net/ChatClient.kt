package cn.olfact.ai.net

import cn.olfact.ai.AiProvider
import cn.olfact.ai.ChatDelta
import cn.olfact.ai.ChatMessage
import cn.olfact.ai.ChatParams
import cn.olfact.ai.TokenUsage
import cn.olfact.ai.error.AiError
import cn.olfact.ai.error.AiErrorMapper
import cn.olfact.core.net.AppDns
import cn.olfact.core.network.requireAtMost
import cn.olfact.core.network.withCancellableResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retryWhen
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

/** OpenAI-compatible SSE and non-streaming requests. Only retry before any result text. */
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
        // 定义在重试边界之外：channelFlow 会随每次 retryWhen 重新执行，局部变量每次归零，
        // 重试就会在“已经吐过正文”后再来一遍（实测 3 次请求、正文重复）。正文是否出现必须跨重试保留。
        var emittedText = false
        channelFlow<ChatDelta> {
            try {
                val request = buildRequest(provider, apiKey, OpenAiWire.requestBody(provider, messages, params, stream = true))
                client.newCall(request).withCancellableResponse { response ->
                    checkHttp(response)
                    val source = response.body.source()
                    val framer = SseFramer()
                    var hasText = false
                    var finish: String? = null

                    suspend fun accept(event: SseEvent): Boolean {
                        val data = event.data.trim()
                        if (data == "[DONE]") return true
                        if (data.isEmpty()) return false
                        OpenAiWire.parseError(data)?.let { throw AiError.Unknown(it) }
                        val delta = OpenAiWire.parseChunk(data) ?: return false
                        if (delta.text.isNotBlank()) hasText = true
                        delta.finishReason?.let { finish = it }
                        send(delta.copy(done = false))
                        return false
                    }

                    var done = false
                    while (!done) {
                        currentCoroutineContext().ensureActive()
                        if (source.exhausted()) {
                            done = framer.end()?.let { accept(it) } == true
                            break
                        }
                        val newline = source.indexOf('\n'.code.toByte(), 0, MAX_SSE_LINE_BYTES + 1)
                        val line = if (newline >= 0) {
                            source.readUtf8LineStrict(MAX_SSE_LINE_BYTES)
                        } else {
                            if (source.request(MAX_SSE_LINE_BYTES + 1)) throw AiError.Unknown("AI 响应单行过大")
                            source.readUtf8()
                        }
                        val event = framer.feedLine(line) ?: continue
                        done = accept(event)
                    }
                    if (!done && finish == null) throw AiError.Network("AI 响应意外中断，请重试")
                    checkFinish(finish)
                    if (!hasText) throw AiError.Unknown("服务端返回了空内容")
                    send(ChatDelta(done = true, finishReason = finish))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                throw AiErrorMapper.fromThrowable(e)
            }
        }.buffer(0).retryWhen { error, attempt ->
            val retry = !emittedText && attempt < MAX_RETRIES && AiErrorMapper.isRetryable(error)
            if (retry) delay(RETRY_BASE_MS * (attempt + 1))
            retry
        }.collect { delta ->
            if (delta.text.isNotEmpty()) emittedText = true
            emit(delta)
        }
    }

    /** 一次性返回（用于分块摘要等中间步骤）。 */
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
    ): String = try {
        val request = buildRequest(provider, apiKey, OpenAiWire.requestBody(provider, messages, params, stream = false))
        client.newCall(request).withCancellableResponse { response ->
            checkHttp(response)
            val body = response.body.requireAtMost(MAX_COMPLETION_BYTES).string()
            OpenAiWire.parseError(body)?.let { throw AiError.Unknown(it) }
            checkFinish(OpenAiWire.parseCompletionFinishReason(body))
            OpenAiWire.parseCompletion(body)?.takeIf { it.isNotBlank() }
                ?: throw AiError.Unknown("服务端返回了空内容")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        throw AiErrorMapper.fromThrowable(e)
    }

    private fun checkHttp(response: Response) {
        if (response.isSuccessful) return
        val errorBody = response.peekBody(MAX_ERROR_BYTES).string()
        throw AiErrorMapper.fromHttp(response.code, errorBody)
    }

    private fun checkFinish(reason: String?) {
        when (reason) {
            "length" -> throw AiError.Unknown("结果达到供应商输出上限，请调高最大 Token 数后重试")
            "content_filter" -> throw AiError.Unknown("供应商过滤了部分输出，无法取得完整结果")
        }
    }

    private fun buildRequest(provider: AiProvider, apiKey: String?, body: String): Request {
        if (!provider.enabled) throw AiError.ProviderNotConfigured("供应商「${provider.name}」已禁用")
        val key = apiKey?.takeIf { it.isNotBlank() }
        if (key == null && provider.requiresApiKey()) {
            throw AiError.ProviderNotConfigured("供应商「${provider.name}」还没填 API Key")
        }
        val builder = Request.Builder()
            .url(provider.endpoint())
            .post(body.toRequestBody(JSON))
        key?.let { builder.header("Authorization", "Bearer $it") }
        provider.extraHeaders.forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val MAX_RETRIES = 2L
        private const val RETRY_BASE_MS = 900L
        private const val MAX_ERROR_BYTES = 16L * 1024
        private const val MAX_SSE_LINE_BYTES = 1024L * 1024
        private const val MAX_COMPLETION_BYTES = 4L * 1024 * 1024

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .dns(AppDns)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()

        fun mergeUsage(current: TokenUsage?, incoming: TokenUsage?): TokenUsage? = incoming ?: current
    }
}
