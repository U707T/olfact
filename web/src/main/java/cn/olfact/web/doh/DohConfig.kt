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
    val preferredUpstreamId: String? = null,

    /**
     * 用户自定义上游（设置页「自定义」）。参与竞速与分流。
     *
     * 同一个地址会以「国内 / 国外」两条身份各注册一次，这样无论规则把某个域名判到哪个池，
     * 它都能参选——不需要让用户理解我们的池子划分。
     * 它没有内置 bootstrap IP，域名解析仍走系统 DNS（用 IP 形式的 DoH 地址可避开这个依赖）。
     */
    val customUpstreams: List<DohUpstream> = emptyList(),

    /**
     * true = 只用 [customUpstreams]，不再并入内置上游。
     * 自建 DoH（AdGuard Home / NextDNS 之类）通常希望“就它一个”，混入内置池反而会把请求分流走。
     */
    val customOnly: Boolean = false
) {
    fun ecs(): EcsSubnet? = ecsSubnet?.let(EcsSubnet::parse)
}
