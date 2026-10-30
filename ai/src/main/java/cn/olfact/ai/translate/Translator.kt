package cn.olfact.ai.translate

import cn.olfact.ai.AiProvider
import cn.olfact.ai.ChatMessage
import cn.olfact.ai.ChatParams
import cn.olfact.ai.net.ChatClient
import cn.olfact.ai.summarize.Prompts
import cn.olfact.ai.summarize.TextChunker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * 划词 / 整页翻译的统一入口。
 * 长文本自动分块（保持段落结构），逐块流式吐译文。
 */
class Translator(
    private val chatClient: ChatClient = ChatClient(),
) {

    fun translate(
        provider: AiProvider,
        apiKey: String?,
        text: String,
        targetLanguage: String? = null,
    ): Flow<String> = flow {
        val target = targetLanguage?.takeIf { it.isNotBlank() } ?: detectTarget(text)
        val chunks = TextChunker.chunk(text, maxChars = MAX_CHUNK_CHARS, overlapChars = 0)
        if (chunks.isEmpty()) return@flow
        chunks.forEachIndexed { index, chunk ->
            if (index > 0) emit("\n\n")
            chatClient
                .stream(
                    provider,
                    apiKey,
                    buildMessages(target, chunk),
                    ChatParams(temperature = 0.2, maxTokens = provider.maxTokens),
                )
                .collect { delta ->
                    if (delta.text.isNotEmpty()) emit(delta.text)
                }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun translateText(
        provider: AiProvider,
        apiKey: String?,
        text: String,
        targetLanguage: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val target = targetLanguage?.takeIf { it.isNotBlank() } ?: detectTarget(text)
        val chunks = TextChunker.chunk(text, maxChars = MAX_CHUNK_CHARS, overlapChars = 0)
        val builder = StringBuilder()
        for (chunk in chunks) {
            if (builder.isNotEmpty()) builder.append("\n\n")
            builder.append(
                chatClient.complete(
                    provider,
                    apiKey,
                    buildMessages(target, chunk),
                    ChatParams(temperature = 0.2, maxTokens = provider.maxTokens, stream = false),
                ).trim()
            )
        }
        builder.toString()
    }

    private fun buildMessages(target: String, text: String): List<ChatMessage> = listOf(
        ChatMessage.system(Prompts.translate(target)),
        ChatMessage.user(text),
    )

    /** 中文占多数 → 译成英文；否则译成简体中文。 */
    fun detectTarget(text: String): String {
        if (text.isBlank()) return "简体中文"
        val cjk = text.count { it.code in 0x4E00..0x9FFF }
        val letters = text.count { it.isLetter() }
        return if (letters > 0 && cjk.toDouble() / letters >= 0.3) "English" else "简体中文"
    }

    companion object {
        private const val MAX_CHUNK_CHARS = 3500
    }
}
