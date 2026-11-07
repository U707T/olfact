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

    /**
     * 单个供应商的 API Key（已解密）。[all] 不再带 key：列表、备份导出、
     * 下拉选择都不需要明文，只有「渲染末尾 4 位」和「发请求」才按 id 取。
     */
    fun apiKey(id: String): String? = all().firstOrNull { it.id == id }?.apiKey
    fun save(provider: AiProvider)
    fun remove(id: String)
    fun setEnabled(id: String, enabled: Boolean)
    fun activeId(): String?
    fun setActive(id: String?)
}
