package cn.olfact.core.api

import kotlinx.coroutines.flow.Flow

/**
 * 设置读写。B1 提供实现（DataStore），其他批次只读不写实现。
 *
 * 线程约定：
 * - [get] 为**同步**读取内存快照，可在主线程调用；进程刚启动、快照未加载完成时返回 [SettingsKey.default]。
 * - [set] 立即更新内存快照并异步落盘，不阻塞调用方。
 * - [changes] 在 set 之后发出 key 名（去重由订阅方自己做）。
 */
interface SettingsRepository {
    fun <T> get(key: SettingsKey<T>): T

    fun <T> set(key: SettingsKey<T>, value: T)

    /** key 名变化流，例如 "doh.mode"。首次订阅不会收到历史值。 */
    val changes: Flow<String>

    /** 清空所有设置回到默认值（书签/历史/脚本等业务数据不受影响）。 */
    fun reset() {}
}

/**
 * 一个设置项定义。类型由 [default] 决定，DataStore 按类型落地
 * （Boolean / Int / Long / Float / String / Set<String>）。
 */
class SettingsKey<T>(val name: String, val default: T) {
    override fun toString(): String = "SettingsKey($name)"
}
