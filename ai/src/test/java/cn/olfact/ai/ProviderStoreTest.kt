package cn.olfact.ai

import cn.olfact.ai.crypto.InMemorySecretBox
import cn.olfact.ai.crypto.SecretBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ProviderStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `disabled and removed providers are not resurrected`() {
        val file = File(temporary.root, "providers.json")
        val secrets = InMemorySecretBox()
        val store = ProviderStore(file, secrets)
        store.list().forEach { store.upsert(it.copy(enabled = false)) }
        assertNull(store.selected())
        store.list().forEach { store.remove(it.id) }
        assertTrue(ProviderStore(file, secrets).list().isEmpty())
    }

    @Test
    fun `failed configuration write does not store a new secret`() {
        val secrets = InMemorySecretBox()
        val provider = AiProvider.presets().first()
        val directory = temporary.newFolder("providers.json")
        val store = ProviderStore(directory, secrets)
        assertTrue(runCatching { store.upsert(provider, "test") }.isFailure)
        assertNull(store.apiKey(provider))
        assertTrue(directory.isDirectory)
    }

    @Test
    fun `secret write failure restores metadata`() {
        val file = File(temporary.root, "providers.json")
        val keyFailure = object : SecretBox {
            override fun put(key: String, value: String) { throw IOException("secret write failure") }
            override fun get(key: String): String? = null
            override fun remove(key: String) = Unit
        }
        val store = ProviderStore(file, keyFailure)
        val original = store.list().first()
        val altered = original.copy(model = "replacement")
        assertTrue(runCatching { store.upsert(altered, "test") }.isFailure)
        assertEquals(original, store.get(original.id))
        assertNull(store.selectedId())
    }

    @Test
    fun `loopback preset does not require a key and endpoints retain query`() {
        val ollama = AiProvider.presets().first { it.id == "ollama" }
        assertFalse(ollama.requiresApiKey())
        assertTrue(ollama.copy(baseUrl = "http://example.invalid").requiresApiKey())
        assertEquals("https://example.invalid/v1/chat/completions?route=a", ollama.copy(baseUrl = "https://example.invalid/v1/?route=a").endpoint())
    }
}
