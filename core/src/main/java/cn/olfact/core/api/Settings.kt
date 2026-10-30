package cn.olfact.core.api

import kotlinx.coroutines.flow.Flow

/**
 * 设置项的类型化 key。
 *
 * 用法：
 * ```
 * object Keys { val NIGHT = PrefKey("ui.night", false) }
 * val night = settings.get(Keys.NIGHT)
 * ```
 *
 * 约定：`key` 全局唯一，模块内加前缀（`web.` / `dl.` / `ad.` / `ai.` / `doh.` / `ui.`）。
 */
interface SettingsKey<T> {
    val key: String
    val def: T
}

data class PrefKey<T>(override val key: String, override val def: T) : SettingsKey<T>

/**
 * 设置仓库。B1 提供实现，其余模块只读；写入只在设置页发生。
 *
 * [changes] 在任意设置被写入后发射该设置的 key，UI 据此做局部刷新。
 */
interface SettingsRepository {
    fun <T> get(key: SettingsKey<T>): T
    fun <T> set(key: SettingsKey<T>, value: T)
    val changes: Flow<String>
}

/** 便捷读取：missing 时回落到 key 的默认值。 */
fun <T> SettingsRepository.getOrDefault(key: SettingsKey<T>): T = get(key)

/** 批量写，只发一次变更。 */
fun SettingsRepository.setAll(vararg pairs: Pair<SettingsKey<*>, Any?>) {
    pairs.forEach { (k, v) -> @Suppress("UNCHECKED_CAST") set(k as SettingsKey<Any?>, v) }
}
