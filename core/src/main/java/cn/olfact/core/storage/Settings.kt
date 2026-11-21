package cn.olfact.core.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cn.olfact.core.api.SettingsKey
import cn.olfact.core.api.SettingsRepository
import cn.olfact.core.log.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private val Context.olfactSettingsStore by preferencesDataStore(name = "olfact_settings")

/** Single process owner. Ordered writes preserve immediate reads without exposing a stale disk snapshot. */
class DataStoreSettingsRepository internal constructor(
    private val store: DataStore<Preferences>,
    scope: CoroutineScope,
) : SettingsRepository {
    constructor(
        context: Context,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    ) : this(context.applicationContext.olfactSettingsStore, scope)

    private class Edit(
        val values: Map<String, Any?> = emptyMap(),
        val clear: Boolean = false,
        val overwrite: Boolean = true,
        val acknowledgement: CompletableDeferred<Int>? = null,
        val isImport: Boolean = false,
    ) {
        fun valuesToWrite(current: Map<String, Any>): Map<String, Any?> = values.filter { (key, value) ->
            val old = current[key]
            if (isImport && old != null) overwrite && compatibleType(old, value) else true
        }
    }

    private val lock = Any()
    private val queue = Channel<Edit>(Channel.UNLIMITED)
    private val pending = LinkedHashSet<Edit>()
    private var persisted: Map<String, Any> = emptyMap()
    @Volatile private var snapshot: Map<String, Any> = emptyMap()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()
    private val emitter = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val changes: Flow<String> = emitter

    init {
        scope.launch {
            try {
                val initial = store.data.first().toValues()
                synchronized(lock) {
                    persisted = initial
                    publishSnapshot()
                }
                _loaded.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(e, "settings 读取失败，按默认值运行")
            }
            for (edit in queue) persist(edit)
        }.invokeOnCompletion { error ->
            synchronized(lock) {
                queue.close(error)
                pending.forEach { it.acknowledgement?.cancel() }
                pending.clear()
                publishSnapshot()
            }
        }
    }

    private fun enqueue(edit: Edit) = synchronized(lock) {
        queue.trySend(edit).getOrThrow()
        pending.add(edit)
        publishSnapshot()
    }

    private fun publishSnapshot() {
        val values = persisted.toMutableMap()
        for (edit in pending) {
            if (edit.acknowledgement?.isCancelled == true || edit.isImport) continue
            if (edit.clear) values.clear()
            for ((key, value) in edit.values) {
                if (value == null) values.remove(key) else values[key] = value
            }
        }
        snapshot = values
    }

    private suspend fun persist(edit: Edit) {
        if (edit.acknowledgement?.isCancelled == true) {
            synchronized(lock) { pending.remove(edit); publishSnapshot() }
            return
        }
        try {
            var written = emptySet<String>()
            val updated = store.edit { prefs ->
                val values = edit.valuesToWrite(prefs.toValues())
                written = if (edit.clear) prefs.toValues().keys else values.keys
                if (edit.clear) prefs.clear()
                values.forEach { (key, value) -> prefs.writeValue(key, value) }
            }.toValues()
            synchronized(lock) {
                persisted = updated
                pending.remove(edit)
                publishSnapshot()
            }
            _loaded.value = true
            edit.acknowledgement?.complete(written.size)
            written.forEach { emitter.emit(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            synchronized(lock) { pending.remove(edit); publishSnapshot() }
            edit.acknowledgement?.completeExceptionally(e)
            Log.e(e, "设置写入失败：${edit.values.keys}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> get(key: SettingsKey<T>): T {
        val raw = snapshot[key.name] ?: return key.default
        val default = key.default
        if (default != null && !compatibleType(default, raw)) {
            Log.w("设置项类型不匹配：${key.name}，回退默认值")
            return default
        }
        return raw as T
    }

    override fun <T> set(key: SettingsKey<T>, value: T) {
        enqueue(Edit(mapOf(key.name to value)))
    }

    override fun reset() {
        enqueue(Edit(clear = true))
    }

    /** Backups use the same snapshot visible to the UI. */
    fun exportMap(): Map<String, String> = snapshot.mapValues { (_, value) -> encodeValue(value) }

    /** Decode before writing and persist in one DataStore transaction. */
    suspend fun importMap(values: Map<String, String>, overwrite: Boolean = false): Int {
        val decoded = values.mapNotNull { (name, raw) -> decodeValue(raw)?.let { name to it } }.toMap()
        if (decoded.isEmpty()) return 0
        val acknowledgement = CompletableDeferred<Int>()
        enqueue(Edit(decoded, overwrite = overwrite, acknowledgement = acknowledgement, isImport = true))
        return try {
            acknowledgement.await()
        } finally {
            if (!acknowledgement.isCompleted) acknowledgement.cancel()
        }
    }

    suspend fun awaitLoaded(timeoutMs: Long = 3_000): Boolean =
        withTimeoutOrNull(timeoutMs) { loaded.first { it } } ?: false

    private fun Preferences.toValues(): Map<String, Any> =
        asMap().entries.associate { (key, value) -> key.name to value }

    private companion object {
        const val VALUE_SEP = "\u001F"

        fun compatibleType(first: Any, second: Any?): Boolean =
            if (first is Set<*>) second is Set<*> else second != null && first.javaClass == second.javaClass

        fun encodeValue(value: Any): String = when (value) {
            is Boolean -> "b:$value"
            is Int -> "i:$value"
            is Long -> "l:$value"
            is Float -> "f:$value"
            is Set<*> -> "ss:" + value.joinToString(VALUE_SEP)
            else -> "s:$value"
        }

        fun decodeValue(raw: String): Any? {
            val separator = raw.indexOf(':')
            if (separator <= 0) return null
            val body = raw.substring(separator + 1)
            return when (raw.substring(0, separator)) {
                "b" -> body.toBooleanStrictOrNull()
                "i" -> body.toIntOrNull()
                "l" -> body.toLongOrNull()
                "f" -> body.toFloatOrNull()?.takeIf { it.isFinite() }
                "ss" -> body.split(VALUE_SEP).filter { it.isNotEmpty() }.toSet()
                "s" -> body
                else -> null
            }
        }
    }

    private fun MutablePreferences.writeValue(name: String, value: Any?) {
        when (value) {
            null -> remove(stringPreferencesKey(name))
            is Boolean -> this[booleanPreferencesKey(name)] = value
            is Int -> this[intPreferencesKey(name)] = value
            is Long -> this[longPreferencesKey(name)] = value
            is Float -> this[floatPreferencesKey(name)] = value
            is String -> this[stringPreferencesKey(name)] = value
            is Set<*> -> this[stringSetPreferencesKey(name)] = value.filterIsInstance<String>().toSet()
            else -> throw IllegalArgumentException("不支持的设置类型：$name")
        }
    }
}
