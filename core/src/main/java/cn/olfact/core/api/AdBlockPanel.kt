package cn.olfact.core.api

/**
 * 拦截面板的只读统计。字段对应 B5 设置页要显示的那几项，
 * 由 B4 的 `AdBlockManager.state` 适配而来（见 app 侧 `ModuleBackends`）。
 */
data class AdBlockStats(
    val enabled: Boolean = false,
    val totalBlocked: Long = 0,
    val todayBlocked: Long = 0,
    val ruleCount: Int = 0,
    val subscriptionCount: Int = 0,
    val lastUpdatedAt: Long = 0,
    val engineReady: Boolean = false,
    val engineName: String = ""
)

/** 订阅条目（面板展示用）。 */
data class AdBlockSubscription(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val ruleCount: Int = 0
)

/**
 * 设置页可写的控制面。实现方：app 侧的适配器，转发给 B4 的 `AdBlockManager`。
 *
 * 与 [AdBlockHook] 的分工：`AdBlockHook` 是请求链路的热路径（同步、快），
 * 这里只服务设置页（允许慢、允许 IO）。
 */
interface AdBlockController {
    fun stats(): AdBlockStats
    fun setEnabled(enabled: Boolean)
    fun subscriptions(): List<AdBlockSubscription>
    fun setSubscriptionEnabled(id: String, enabled: Boolean)
    fun addSubscription(name: String, url: String)
    fun removeSubscription(id: String)
    fun refresh()
    fun whitelist(): List<String>
    fun setWhitelist(hosts: List<String>)
}
