package cn.olfact.web.doh

import kotlinx.coroutines.flow.StateFlow
import okhttp3.Dns
import okhttp3.OkHttpClient

/**
 * DoH 总控（TECH-PLAN §4.4 的接口约定 + 供 UI 观察的状态流）。
 */
interface DohManager {

    fun start(cfg: DohConfig)

    fun stop()

    fun status(): DohStatus

    fun clearCache()

    /** 重新竞速，切换最快上游 */
    fun rerace()

    /** L2 是否真的生效（已挂上 ProxyController 且隧道在跑） */
    fun isProxying(): Boolean

    /** 供设置页 / 地址栏图标订阅 */
    val statusFlow: StateFlow<DohStatus>

    /** 当前分流规则 */
    fun rules(): DnsRules

    /** 地址栏长按「本站启用加密 DNS」 */
    fun enableForHost(host: String): DnsRules

    fun updateRules(rules: DnsRules)

    /** L1：给下载/嗅探/AI 复用的 Dns，未启动时返回 null */
    fun appDns(): Dns?

    /** 便捷方法：把 OkHttpClient 的 dns 换成 DoH 分流实现 */
    fun wrap(client: OkHttpClient): OkHttpClient
}
