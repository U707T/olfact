package cn.olfact.ai.summarize

import cn.olfact.ai.AiProvider
import cn.olfact.ai.ChatMessage
import cn.olfact.ai.TokenUsage
import cn.olfact.ai.net.ChatClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

data class SummarizeParams(
    val language: String = "中文",
    val maxBullets: Int = 7,
    val chunkChars: Int = 6000,
    /** 分块摘要阶段并发数；太大容易被限流。 */
    val chunkConcurrency: Int = 1,
)

data class SummaryResult(
    val title: String,
    val markdown: String,
    val chunkCount: Int,
    val usage: TokenUsage? = null,
    val article: Article? = null,
) {
    val bullets: List<String>
        get() = markdown.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("- ") }
            .map { it.removePrefix("- ").trim() }
            .filter { it.isNotEmpty() }
            .toList()
}

sealed interface SummaryEvent {
    data class Progress(val stage: String, val index: Int, val total: Int) : SummaryEvent

    /** 最终成稿的流式增量（可直接追加到卡片上）。 */
    data class Delta(val text: String) : SummaryEvent

    data class Done(val result: SummaryResult) : SummaryEvent

    data class Failed(val error: Throwable) : SummaryEvent
}

/**
 * 网页总结：Readability 抽正文 → 分块 → 逐块摘要 → 合并成稿（流式）。
 * 短文章直接单次流式；长文章先并发（默认串行）压成要点，再合成，避免超上下文。
 */
class WebSummarizer(
    private val chatClient: ChatClient = ChatClient(),
) {

    fun summarize(
        provider: AiProvider,
        apiKey: String?,
        article: Article,
        params: SummarizeParams = SummarizeParams(),
    ): Flow<SummaryEvent> = flow {
        try {
            val text = article.text.trim()
            if (text.isEmpty()) {
                emit(SummaryEvent.Failed(IllegalArgumentException("这个页面没有抽到正文")))
                return@flow
            }

            val chunks = TextChunker.chunk(text, params.chunkChars)
            val system = provider.systemPrompt?.takeIf { it.isNotBlank() }

            if (chunks.size <= 1) {
                emit(SummaryEvent.Progress("总结中", 1, 1))
                val messages = buildList {
                    system?.let { add(ChatMessage.system(it)) }
                    add(ChatMessage.system(Prompts.directSummary(params.language, params.maxBullets)))
                    add(ChatMessage.user(text))
                }
                val result = streamFinal(provider, apiKey, messages) { text ->
                    emit(SummaryEvent.Delta(text))
                }
                emit(SummaryEvent.Done(SummaryResult(article.title, result.first, 1, result.second, article)))
                return@flow
            }

            val partials = ArrayList<String>(chunks.size)
            var usage: TokenUsage? = null
            for ((index, chunk) in chunks.withIndex()) {
                emit(SummaryEvent.Progress("分段摘要", index + 1, chunks.size))
                val messages = buildList {
                    system?.let { add(ChatMessage.system(it)) }
                    add(ChatMessage.user(Prompts.chunkSummary(index + 1, chunks.size, params.language)))
                    add(ChatMessage.user(chunk))
                }
                val content = withContext(Dispatchers.IO) {
                    chatClient.complete(
                        provider,
                        apiKey,
                        messages,
                        cn.olfact.ai.ChatParams(
                            temperature = 0.2,
                            maxTokens = provider.maxTokens.coerceAtMost(800),
                            topP = 1.0,
                            stream = false,
                        ),
                    )
                }
                partials += content.trim()
            }

            emit(SummaryEvent.Progress("合并成稿", chunks.size, chunks.size))
            val messages = buildList {
                system?.let { add(ChatMessage.system(it)) }
                add(ChatMessage.system(Prompts.finalSummary(params.language, params.maxBullets)))
                add(ChatMessage.user(partials.joinToString("\n\n")))
            }
            val finalResult = streamFinal(provider, apiKey, messages) { text ->
                emit(SummaryEvent.Delta(text))
            }
            usage = finalResult.second
            emit(SummaryEvent.Done(SummaryResult(article.title, finalResult.first, chunks.size, usage, article)))
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            emit(SummaryEvent.Failed(t))
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun streamFinal(
        provider: AiProvider,
        apiKey: String?,
        messages: List<ChatMessage>,
        onDelta: suspend (String) -> Unit,
    ): Pair<String, TokenUsage?> {
        val builder = StringBuilder()
        var usage: TokenUsage? = null
        chatClient
            .stream(
                provider,
                apiKey,
                messages,
                cn.olfact.ai.ChatParams(temperature = 0.3, maxTokens = provider.maxTokens),
            )
            .collect { delta ->
                if (delta.text.isNotEmpty()) {
                    builder.append(delta.text)
                    onDelta(delta.text)
                }
                if (delta.usage != null) usage = delta.usage
            }
        return builder.toString().trim() to usage
    }
}
