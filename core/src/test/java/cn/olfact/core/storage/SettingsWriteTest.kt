package cn.olfact.core.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import cn.olfact.core.api.SettingsKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SettingsWriteTest {
    private val flag = SettingsKey("flag", false)
    private val number = SettingsKey("number", 0)

    private class FakeStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        override val data = MutableStateFlow(initial)
        var blockFirst: CompletableDeferred<Unit>? = null
        val firstStarted = CompletableDeferred<Unit>()
        var attempts = 0
        var fail = false
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            if (attempts++ == 0) {
                firstStarted.complete(Unit)
                blockFirst?.await()
            }
            if (fail) throw IOException("disk not writable")
            return transform(data.value).also { data.value = it }
        }
    }

    @Test
    fun `reset and set are serialized and import waits for durable commit`() = runBlocking {
        val fake = FakeStore().apply { blockFirst = CompletableDeferred() }
        val owner = CoroutineScope(coroutineContext + Job())
        try {
            val repo = DataStoreSettingsRepository(fake, owner)
            assertTrue(repo.awaitLoaded())
            repo.set(flag, true)
            fake.firstStarted.await()
            repo.reset()
            repo.set(number, 17)
            assertFalse(repo.get(flag))
            assertEquals(17, repo.get(number))
            val restore = async { repo.importMap(mapOf("number" to "i:24", "flag" to "b:true")) }
            assertFalse(restore.isCompleted)
            fake.blockFirst!!.complete(Unit)
            assertEquals(1, withTimeout(3_000) { restore.await() })
            assertEquals(4, fake.attempts)
            assertEquals(17, repo.get(number))
            assertTrue(repo.get(flag))
        } finally {
            owner.cancel()
        }
    }

    @Test
    fun `wrong types do not crash getters and failed restore returns an error`() = runBlocking {
        val wrong = emptyPreferences().toMutablePreferences().also { it[stringPreferencesKey("number")] = "oops" }
        val fake = FakeStore(wrong)
        val owner = CoroutineScope(coroutineContext + Job())
        try {
            val repo = DataStoreSettingsRepository(fake, owner)
            assertTrue(repo.awaitLoaded())
            assertEquals(0, repo.get(number))
            assertEquals(0, repo.importMap(mapOf("flag" to "b:maybe", "other" to "x:1"), overwrite = true))
            fake.fail = true
            val failed = runCatching { repo.importMap(mapOf("flag" to "b:true"), overwrite = true) }
            assertTrue(failed.exceptionOrNull() is IOException)
            assertFalse(repo.get(flag))
        } finally {
            owner.cancel()
        }
    }
}
