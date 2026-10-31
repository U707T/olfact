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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** 进程内唯一的一份设置库，文件名固定，别处不要再建第二个。 */
private val Context.olfactSettingsStore by preferencesDataStore(name = "olfact_settings")

/**
 * [SettingsRepository] 的 DataStore 实现。
 *
 * - 启动时异步加载到内存快照，之后 [get] 零开销；
 * - [set] 先改快照再异步落盘，UI 不会卡；
 * - 未加载完成时读到 [SettingsKey.default]，属预期行为。
 */
class DataStoreSettingsRepository(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : SettingsRepository {

    private val store: DataStore<Preferences> = context.applicationContext.olfactSettingsStore

    @Volatile
    private var snapshot: Map<String, Any> = emptyMap()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val emitter = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val changes: Flow<String> = emitter

    init {
        store.data
            .catch { e ->
                // 文件损坏：不要让整个应用起不来，清空后用默认值继续
                Log.e(e, "settings 读取失败，按默认值运行")
            }
            .onEach { prefs ->
                snapshot = prefs.asMap().entries.associate { (key, value) -> key.name to (value as Any) }
                _loaded.value = true
            }
            .launchIn(scope)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> get(key: SettingsKey<T>): T {
        val raw = snapshot[key.name] ?: return key.default
        return try {
            raw as T
        } catch (e: ClassCastException) {
            Log.w(e, "设置项类型不匹配：${key.name}，回退默认值")
            key.default
        }
    }

    override fun <T> set(key: SettingsKey<T>, value: T) {
        snapshot = snapshot + (key.name to (value as Any))
        scope.launch {
            try {
                store.edit { prefs -> prefs.writeValue(key.name, value) }
                emitter.emit(key.name)
            } catch (e: IOException) {
                Log.e(e, "设置写入失败：${key.name}")
            }
        }
    }

    /**
     * 备份导出：把内存快照编码成 `类型:值` 的字符串表（备份格式是 Map<String,String>）。
     *
     * 类型是必须的——DataStore 的 key 不带类型信息，直接塞字符串会在恢复时写错类型。
     */
    fun exportMap(): Map<String, String> = snapshot.mapValues { (_, v) -> encodeValue(v) }

    /**
     * 备份导入。默认只补缺（`overwrite = false`），避免恢复旧备份把当前设置冲掉。
     *
     * @return 实际写入的项数
     */
    fun importMap(values: Map<String, String>, overwrite: Boolean = false): Int {
        var written = 0
        for ((name, raw) in values) {
            val sep = raw.indexOf(':')
            if (sep <= 0) continue
            if (!overwrite && snapshot.containsKey(name)) continue
            val type = raw.substring(0, sep)
            val body = raw.substring(sep + 1)
            val ok = runCatching {
                when (type) {
                    "b" -> set(SettingsKey(name, false), body.toBoolean())
                    "i" -> set(SettingsKey(name, 0), body.toInt())
                    "l" -> set(SettingsKey(name, 0L), body.toLong())
                    "f" -> set(SettingsKey(name, 0f), body.toFloat())
                    "ss" -> set(
                        SettingsKey(name, emptySet<String>()),
                        body.split(VALUE_SEP).filter { it.isNotEmpty() }.toSet()
                    )
                    else -> set(SettingsKey(name, ""), body)
                }
                true
            }.getOrDefault(false)
            if (ok) written++
        }
        return written
    }

    private fun encodeValue(v: Any): String = when (v) {
        is Boolean -> "b:$v"
        is Int -> "i:$v"
        is Long -> "l:$v"
        is Float -> "f:$v"
        is Set<*> -> "ss:" + v.joinToString(VALUE_SEP)
        else -> "s:$v"
    }

    /** 冷启动需要"读到真值再渲染"的地方，先 await 一下（最多 [timeoutMs]）。 */
    suspend fun awaitLoaded(timeoutMs: Long = 3_000): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (!_loaded.value) kotlinx.coroutines.delay(8)
            true
        } ?: false

    private companion object {
        /** 集合成员分隔符（U+001F，正常设置值里不会出现）。 */
        const val VALUE_SEP = "\u001F"
    }

    private fun MutablePreferences.writeValue(name: String, value: Any?) {
        when (value) {
            null -> remove(stringPreferencesKey(name))
            is Boolean -> this[booleanPreferencesKey(name)] = value
            is Int -> this[intPreferencesKey(name)] = value
            is Long -> this[longPreferencesKey(name)] = value
            is Float -> this[floatPreferencesKey(name)] = value
            is String -> this[stringPreferencesKey(name)] = value
            is Set<*> -> this[stringSetPreferencesKey(name)] =
                value.filterNotNull().mapTo(LinkedHashSet()) { it.toString() }
            else -> Log.w("不支持的设置类型 ${value::class.java.name}，已忽略：$name")
        }
    }
}
