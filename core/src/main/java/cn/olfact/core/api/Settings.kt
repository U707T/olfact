package cn.olfact.core.api

import kotlinx.coroutines.flow.Flow

/** 类型安全的设置键。name 全局唯一，default 在未写入时返回。 */
interface SettingsKey<T> {
    val name: String
    val default: T
}

/**
 * 设置仓库。B1 提供实现，其余模块只读；写入统一走这里以保证变更可被观察。
 */
interface SettingsRepository {
    fun <T> get(key: SettingsKey<T>): T
    fun <T> set(key: SettingsKey<T>, value: T)

    /** 变更事件，值为 [SettingsKey.name]。 */
    val changes: Flow<String>
}

/** 便捷构造器，业务侧声明自己的键时用。 */
fun <T> settingsKey(name: String, defaultValue: T): SettingsKey<T> = object : SettingsKey<T> {
    override val name: String = name
    override val default: T = defaultValue
}
