package cn.olfact.app.data

import cn.olfact.core.api.SettingsKey
import cn.olfact.core.api.SettingsRepository
import kotlinx.coroutines.flow.MutableSharedFlow

/** Settings facade substitute; tests do not exercise app/Core/DataStore initialization. */
object K {
    val HISTORY_LIMIT = SettingsKey("ui.history_limit", "1000")
    val RESTORE_TABS = SettingsKey("ui.restore_tabs", "manual")
    val SEARCH_ENGINE = SettingsKey("ui.search_engine", "baidu")
    val SEARCH_CUSTOM = SettingsKey("ui.search_custom", "")
    val DOH_ENABLED = SettingsKey("doh.enabled", false)
    val DOH_MODE = SettingsKey("doh.mode", "smart")
    val DOH_UPSTREAM = SettingsKey("doh.upstream", "auto")
    val DOH_RULES = SettingsKey("doh.rules", "")
}
class MemorySettings : SettingsRepository {
    private val data = HashMap<String, Any?>()
    @Suppress("UNCHECKED_CAST") override fun <T> get(key: SettingsKey<T>): T = if (data.containsKey(key.name)) data[key.name] as T else key.default
    override fun <T> set(key: SettingsKey<T>, value: T) { data[key.name] = value }
    override val changes = MutableSharedFlow<String>()
    override fun reset() { data.clear() }
}
object AppSettings {
    val repo = MemorySettings()
    fun <T> get(key: SettingsKey<T>): T = repo.get(key)
    fun <T> set(key: SettingsKey<T>, value: T) = repo.set(key, value)
    fun reset() = repo.reset()
}
