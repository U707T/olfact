package cn.olfact.app.data

import cn.olfact.ai.AiProvider
import cn.olfact.ai.ProviderStore
import cn.olfact.ai.crypto.InMemorySecretBox
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Real adapter and file-backed ProviderStore; only secret storage is in-memory. */
class AiAdapterIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `editing displayed fields retains advanced configuration and existing secret reference`() {
        val file = File(temporary.root, "providers.json")
        val secrets = InMemorySecretBox()
        val store = ProviderStore(file, secrets)
        val original = AiProvider(
            id = "custom", name = "before", baseUrl = "https://old.example/v1", model = "old-model",
            keyRef = "retained-ref", temperature = 0.25, maxTokens = 8192, topP = 0.65,
            systemPrompt = "retained instruction", extraHeaders = mapOf("X-Route" to "private-route")
        )
        store.upsert(original, "example-test-secret")
        val adapter = AiAdapter(store)
        val displayed = adapter.all().first { it.id == original.id }
        assertEquals("", displayed.apiKey)
        adapter.save(displayed.copy(name = "after", baseUrl = "https://new.example/v1", model = "new-model", enabled = false))
        val reloaded = ProviderStore(file, secrets).get(original.id)!!
        assertEquals(original.copy(name = "after", baseUrl = "https://new.example/v1", model = "new-model", enabled = false), reloaded)
        assertEquals("example-test-secret", adapter.apiKey(original.id))
        assertFalse(file.readText().contains("example-test-secret"))
    }

    @Test fun `explicit key replacement and enabled toggles preserve hidden fields`() {
        val file = File(temporary.root, "providers.json")
        val secrets = InMemorySecretBox()
        val store = ProviderStore(file, secrets)
        val original = AiProvider("custom", "provider", "https://api.example/v1", "model", keyRef = "custom-key", maxTokens = 4096,
            extraHeaders = mapOf("X-Test" to "retained"), enabled = false)
        store.upsert(original, "old-test-key")
        val adapter = AiAdapter(store)
        adapter.save(adapter.all().first { it.id == original.id }.copy(apiKey = "new-test-key"))
        adapter.setEnabled(original.id, true)
        assertEquals(original.copy(enabled = true), store.get(original.id))
        assertEquals("new-test-key", secrets.get("custom-key"))
        assertNull(secrets.get("provider_custom"))
        assertTrue(adapter.all().all { it.apiKey.isBlank() })
    }
}
