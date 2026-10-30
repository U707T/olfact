package cn.olfact.web.doh

/**
 * DoH 运行时配置。由设置页（B5）组装，[cn.olfact.web.doh.DohManager.start] 消费。
 */
data class DohConfig(
    /** 总开关；关掉后 L1/L2 都退回系统 DNS */
    val enabled: Boolean = true,
    val mode: DohMode = DohMode.SMART,
    val rules: DnsRules = DnsRules.builtin(DohMode.SMART),
    /** 是否启用 L2（本地隧道 + ProxyController） */
    val useProxy: Boolean = true,
    /** 0 表示随机可用端口 */
    val proxyPort: Int = 0,
    /** 国内池带 ECS，例如 "116.24.0.0/16"；null 表示不带 */
    val ecsSubnet: String? = null,
    /** 单次查询超时，TECH-PLAN 要求 2.5s */
    val timeoutMs: Long = 2_500L,
    val enableIpv6: Boolean = true,
    /** 代理自检用的探测目标（CONNECT 到 443） */
    val probeHost: String = "www.gstatic.com",
    /** 失败多少次触发熔断 */
    val circuitBreakerThreshold: Int = 3,
    /** 上次竞速选中的上游 id，用于启动时先试它 */
    val preferredUpstreamId: String? = null
) {
    fun ecs(): EcsSubnet? = ecsSubnet?.let(EcsSubnet::parse)
}
