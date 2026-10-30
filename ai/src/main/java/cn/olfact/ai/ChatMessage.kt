package cn.olfact.ai

enum class ChatRole(val wireName: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant");

    companion object {
        fun fromWire(name: String?): ChatRole =
            entries.firstOrNull { it.wireName.equals(name, ignoreCase = true) } ?: USER
    }
}

data class ChatMessage(val role: ChatRole, val content: String) {
    companion object {
        fun system(content: String) = ChatMessage(ChatRole.SYSTEM, content)
        fun user(content: String) = ChatMessage(ChatRole.USER, content)
        fun assistant(content: String) = ChatMessage(ChatRole.ASSISTANT, content)
    }
}

data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
)

data class ChatDelta(
    val text: String = "",
    val done: Boolean = false,
    val finishReason: String? = null,
    val usage: TokenUsage? = null,
    val model: String? = null,
)

data class ChatParams(
    val temperature: Double = 0.7,
    val maxTokens: Int = 2048,
    val topP: Double = 1.0,
    val stream: Boolean = true,
    /** 让服务端在最后一帧返回 usage（OpenAI 的 stream_options）。 */
    val includeUsage: Boolean = true,
    val stop: List<String> = emptyList(),
)
