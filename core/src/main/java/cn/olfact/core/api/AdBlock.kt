package cn.olfact.core.api

/**
 * 拦截钩子。B4 实现，B2 在请求管线里调用。
 *
 * 性能约定：实现内部必须无锁快路径，单次判断不超过 0.1 ms（10 万条规则下也不得回退到正则）。
 */
interface AdBlockHook {
    fun shouldBlock(url: String, pageHost: String): Boolean
    fun cosmeticCss(pageHost: String): String
    fun recordBlocked()
}

/** 拦截面板用的只读统计。 */
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

/** 订阅条目。 */
data class AdBlockSubscription(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val ruleCount: Int = 0
)

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
