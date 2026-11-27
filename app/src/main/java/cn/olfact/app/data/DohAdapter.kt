package cn.olfact.app.data

import cn.olfact.core.api.DohConfig
import cn.olfact.core.api.DohController
import cn.olfact.core.api.DohManager
import cn.olfact.core.api.DohMode
import cn.olfact.core.api.DohStatus
import cn.olfact.web.doh.DohManagerImpl
import cn.olfact.web.doh.DnsRules
import cn.olfact.web.doh.DohUpstream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/** `core/api` DoH 契约 → B2 的 `web.doh` 实现。 */
class DohAdapter(
    private val impl: DohManagerImpl,
    private val settings: cn.olfact.core.api.SettingsRepository
) : DohManager, DohController {

    private val settingsLock = Any()
    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appliedUpstream: String? = null
    private var appliedRules: Pair<DohMode, String>? = null

    /** 读取并应用最新设置。较早排队的操作不能拿旧开关或旧上游覆盖较新的选择。 */
    fun applySettings() = synchronized(settingsLock) {
        val mode = DohMode.of(settings.get(K.DOH_MODE))
        val text = settings.get(K.DOH_RULES)
        val url = selectedUpstreamUrl()
        val enabled = settings.get(K.DOH_ENABLED)
        val running = impl.status().running
        if (!enabled && running) impl.stop()
        if (enabled && (!running || url != appliedUpstream)) {
            start(DohConfig(true, mode, listOf(url), text.lines()))
            appliedUpstream = url
        } else if (appliedRules != (mode to text)) {
            impl.updateRules(DnsRules.parse(text, mode.toWeb()))
        }
        appliedRules = mode to text
    }

    /** 配置已经保存；即使用户返回上一页，也要完成应用。 */
    fun applySettingsAsync() = settingsScope.async { applySettings() }

    /** 代理自检、挂载或熔断的状态由 Web 层维护。 */
    fun proxyError(): String? = impl.status().lastError

    /** 当前选中的上游地址（预设或自定义）。 */
    fun selectedUpstreamUrl(): String = settings.get(K.DOH_UPSTREAM).trim()

    /** 命中某个内置上游则返回它；否则视为自定义（auto / 空串返回 null）。 */
    fun presetFor(url: String): cn.olfact.web.doh.DohUpstream? =
        cn.olfact.web.doh.DohUpstream.byUrl(url)

    companion object {
        /** 「自动」：内置上游并发竞速。 */
        const val AUTO = "auto"

        /** 给界面用：判断某个地址是不是内置上游（不需要实例）。 */
        fun isPresetUrl(url: String): Boolean =
            cn.olfact.web.doh.DohUpstream.byUrl(url) != null

        /**
         * 把用户填的地址翻译成池子里的两条上游。
         *
         * 复用设置页的解析：HTTPS 公网上游、本机/内网 HTTP，缺省路径补 `/dns-query`。
         * 不合法返回空表；显式自定义地址由调用方校验后保存。
         */
        fun buildCustomUpstreams(url: String): List<cn.olfact.web.doh.DohUpstream> {
            if (url.trim().equals(AUTO, ignoreCase = true)) return emptyList()
            val parsed = DohUpstreams.parse(url) as? DohUpstreams.Result.Ok ?: return emptyList()
            val clean = parsed.url
            val label = "自定义 · ${parsed.host}"
            return listOf(
                cn.olfact.web.doh.DohUpstream(
                    id = "custom-cn", label = label, url = clean,
                    region = cn.olfact.web.doh.DohUpstream.Region.DOMESTIC,
                    supportsEcs = false, bootstrap = emptyList()
                ),
                cn.olfact.web.doh.DohUpstream(
                    id = "custom-intl", label = label, url = clean,
                    region = cn.olfact.web.doh.DohUpstream.Region.FOREIGN,
                    supportsEcs = false, bootstrap = emptyList()
                )
            )
        }
    }

    /** 自定义上游：同一地址注册成「国内 / 国外」两条身份，让它在任何分池里都能参选。 */
    fun customUpstreams(url: String): List<cn.olfact.web.doh.DohUpstream> = buildCustomUpstreams(url)

    fun shouldUseDoh(host: String): Boolean =
        runCatching { impl.rules().useDoh(host) }.getOrDefault(false)

    override fun start(cfg: DohConfig) {
        val requested = cfg.upstreams.firstOrNull().orEmpty()
        val preset = presetFor(requested)
        val custom = if (preset == null) customUpstreams(requested) else emptyList()
        impl.start(
            cn.olfact.web.doh.DohConfig(
                enabled = cfg.enabled,
                mode = cfg.mode.toWeb(),
                rules = DnsRules.parse(cfg.rules.joinToString("\n"), cfg.mode.toWeb()),
                useProxy = true,
                preferredUpstreamId = preset?.id,
                customUpstreams = custom,
                customOnly = custom.isNotEmpty()
            )
        )
    }

    /**
     * 只切分流模式，规则文本不变：立即重建规则表，不用重启应用。
     * 设置页的三个模式选项之前只写了 DataStore，引擎仍在用旧模式。
     */
    fun applyMode(mode: DohMode) {
        val current = impl.rules()
        impl.updateRules(DnsRules.parse(current.toText(), mode.toWeb()))
        settings.set(K.DOH_MODE, mode.id)
    }

    /** 保存规则文本并立即生效（「保存规则」按钮之前只写 DataStore）。 */
    fun applyRules(text: String, mode: DohMode) {
        impl.updateRules(DnsRules.parse(text, mode.toWeb()))
        settings.set(K.DOH_RULES, text)
        settings.set(K.DOH_MODE, mode.id)
    }

    override fun stop() = impl.stop()

    override fun status(): DohStatus {
        val s = impl.status()
        return DohStatus(
            enabled = s.running,
            mode = s.mode.toCore(),
            upstream = listOfNotNull(s.domesticUpstream, s.foreignUpstream)
                .distinct().joinToString(" / ").ifBlank { "—" },
            avgCostMs = s.avgLatencyMs.toInt(),
            cacheHitRate = (s.cacheHitRate * 100).toInt().coerceIn(0, 100),
            failures = s.failures,
            queriesToday = s.todayQueries,
            proxying = s.proxying,
            lastRaceAt = 0L,
            engineReady = true
        )
    }

    override fun clearCache() = impl.clearCache()

    override fun rerace() = impl.rerace()

    override fun isProxying(): Boolean = impl.isProxying()

    override fun config(): DohConfig {
        val rules = impl.rules()
        return DohConfig(
            // 之前恒 true：界面拿这份配置渲染开关，DoH 已经停了也显示「已开启」。
            enabled = impl.status().running,
            mode = rules.mode.toCore(),
            upstreams = listOf(selectedUpstreamUrl()),
            rules = rules.toText().lines().filter { it.isNotBlank() }
        )
    }

    override fun save(cfg: DohConfig) {
        // 统一走 applyRules：规则与模式一起落到引擎和 DataStore，避免只写一半。
        applyRules(cfg.rules.joinToString("\n"), cfg.mode)
        if (!cfg.enabled) impl.stop()
    }

    override fun importRules(lines: List<String>) {
        val text = lines.joinToString("\n")
        applyRules(text, impl.rules().mode.toCore())
    }

    override fun exportRules(): String = impl.rules().toText().lines()
        .filter { it.isNotBlank() }.joinToString("\n")
}

private fun DohMode.toWeb(): cn.olfact.web.doh.DohMode = when (this) {
    DohMode.SMART -> cn.olfact.web.doh.DohMode.SMART
    DohMode.INCLUDE -> cn.olfact.web.doh.DohMode.ONLY_LISTED
    DohMode.EXCLUDE -> cn.olfact.web.doh.DohMode.EXCLUDE_LISTED
}

private fun cn.olfact.web.doh.DohMode.toCore(): DohMode = when (this) {
    cn.olfact.web.doh.DohMode.SMART -> DohMode.SMART
    cn.olfact.web.doh.DohMode.ONLY_LISTED -> DohMode.INCLUDE
    cn.olfact.web.doh.DohMode.EXCLUDE_LISTED -> DohMode.EXCLUDE
}
