package cn.olfact.ai

/**
 * OpenAI 兼容供应商。baseUrl 支持带不带 `/v1`，[ChatClient] 会自动拼 `/chat/completions`。
 * apiKey 不落在这里：只保存 [keyRef]，真正的密钥进 Keystore 加密的 [cn.olfact.ai.crypto.SecretBox]。
 */
data class AiProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val keyRef: String = "provider_${id}",
    val temperature: Double = 0.7,
    val maxTokens: Int = 2048,
    val topP: Double = 1.0,
    val systemPrompt: String? = null,
    val extraHeaders: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
) {
    fun endpoint(): String {
        val base = baseUrl.trimEnd('/')
        return when {
            base.endsWith("/chat/completions") -> base
            base.endsWith("/v1") -> "$base/chat/completions"
            else -> "$base/v1/chat/completions"
        }
    }

    companion object {
        /** 预置几个常见供应商，用户只需要填 key。 */
        fun presets(): List<AiProvider> = listOf(
            AiProvider("openai", "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
            AiProvider("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
            AiProvider("moonshot", "Moonshot", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
            AiProvider("siliconflow", "SiliconFlow", "https://api.siliconflow.cn/v1", "Qwen/Qwen2.5-7B-Instruct"),
            AiProvider("ollama", "本地 Ollama", "http://127.0.0.1:11434/v1", "qwen2.5:7b"),
        )
    }
}
