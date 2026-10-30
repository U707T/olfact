package cn.olfact.core.api

/** OpenAI 兼容供应商。 */
data class AiProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val enabled: Boolean = true
)

/** AI 面板读写的数据源（B3 实现）。 */
interface ProviderStore {
    fun all(): List<AiProvider>
    fun save(provider: AiProvider)
    fun remove(id: String)
    fun setEnabled(id: String, enabled: Boolean)
    fun activeId(): String?
    fun setActive(id: String?)
}
