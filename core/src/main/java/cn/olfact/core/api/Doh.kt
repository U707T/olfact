package cn.olfact.core.api

/** 分流模式，对应 TECH-PLAN §4.2 的三档策略。 */
enum class DohMode(val id: String) {
    SMART("smart"),      // 智能分流：国内系统 DNS，国外/清单内走 DoH
    INCLUDE("include"),  // 仅指定域名走 DoH
    EXCLUDE("exclude");  // 仅排除域名走系统 DNS

    companion object {
        fun of(id: String?): DohMode = entries.firstOrNull { it.id == id } ?: SMART
    }
}

/** 设置页展示的运行时状态。 */
data class DohStatus(
    val enabled: Boolean = false,
    val mode: DohMode = DohMode.SMART,
    val upstream: String = "—",
    val avgCostMs: Int = 0,
    val cacheHitRate: Int = 0,
    val failures: Long = 0,
    val queriesToday: Long = 0,
    val proxying: Boolean = false,
    val lastRaceAt: Long = 0,
    val engineReady: Boolean = false
)

data class DohConfig(
    val enabled: Boolean,
    val mode: DohMode,
    val upstreams: List<String>,
    val rules: List<String>
)

/** B2 实现。设置页只读 [status]，写操作走 [DohController]。 */
interface DohManager {
    fun start(cfg: DohConfig)
    fun stop()
    fun status(): DohStatus
    fun clearCache()
    fun rerace()
    fun isProxying(): Boolean
}

/** 设置页可写的控制面（B2 实现）。 */
interface DohController {
    fun config(): DohConfig
    fun save(cfg: DohConfig)
    fun importRules(lines: List<String>)
    fun exportRules(): String
}
