package cn.olfact.web.doh

/**
 * 供设置页渲染的快照（TECH-PLAN §4.5-8）。字段名与面板一一对应。
 */
data class DohStatus(
    val running: Boolean = false,
    val proxying: Boolean = false,
    val mode: DohMode = DohMode.SMART,
    val domesticUpstream: String? = null,
    val foreignUpstream: String? = null,
    val avgLatencyMs: Long = 0L,
    val cacheHitRate: Double = 0.0,
    val failures: Long = 0L,
    val todayQueries: Long = 0L,
    val ruleCount: Int = 0,
    val consecutiveFailures: Int = 0,
    val lastError: String? = null
) {
    val encryptedRatioText: String get() = if (proxying) "页面流量已接管" else "仅应用内生效"

    companion object {
        fun idle(): DohStatus = DohStatus()
    }
}
