package cn.olfact.app.data

import cn.olfact.core.api.AiProvider
import cn.olfact.core.api.ProviderStore
import kotlinx.coroutines.flow.map

/** `core/api` AI 面板 → B3 的 `ProviderStore`（密钥仍走 Keystore）。 */
class AiAdapter(private val store: cn.olfact.ai.ProviderStore) : ProviderStore {

    override fun all(): List<AiProvider> = store.list().map { p ->
        AiProvider(
            id = p.id,
            name = p.name,
            baseUrl = p.baseUrl,
            apiKey = store.apiKey(p).orEmpty(),
            model = p.model,
            enabled = p.enabled
        )
    }

    override fun save(provider: AiProvider) {
        val existing = store.get(provider.id)
        store.upsert(
            cn.olfact.ai.AiProvider(
                id = provider.id,
                name = provider.name,
                baseUrl = provider.baseUrl,
                model = provider.model,
                keyRef = existing?.keyRef ?: "provider_${provider.id}",
                enabled = provider.enabled
            ),
            apiKey = provider.apiKey.ifBlank { null }
        )
    }

    override fun remove(id: String) = store.remove(id)

    override fun setEnabled(id: String, enabled: Boolean) {
        val p = store.get(id) ?: return
        store.upsert(p.copy(enabled = enabled))
    }

    override fun activeId(): String? = store.selectedId()

    override fun setActive(id: String?) {
        if (id == null) return
        store.select(id)
    }
}
