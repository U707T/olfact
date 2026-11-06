package cn.olfact.app.data

import cn.olfact.core.api.DohConfig
import cn.olfact.core.api.DohController
import cn.olfact.core.api.DohManager
import cn.olfact.core.api.DohMode
import cn.olfact.core.api.DohStatus
import cn.olfact.web.doh.DohManagerImpl
import cn.olfact.web.doh.DnsRules

/** `core/api` DoH 契约 → B2 的 `web.doh` 实现。 */
class DohAdapter(
    private val impl: DohManagerImpl,
    private val settings: cn.olfact.core.api.SettingsRepository
) : DohManager, DohController {

    /** 用当前设置里的开关/模式/上游跑一次。 */
    fun startFromSettings() {
        val mode = DohMode.of(settings.get(K.DOH_MODE))
        val url = settings.get(K.DOH_UPSTREAM).trim()
        val preset = presetFor(url)
        val custom = customUpstreams(url)
        impl.start(
            cn.olfact.web.doh.DohConfig(
                enabled = true,
                mode = mode.toWeb(),
                rules = DnsRules.parse(settings.get(K.DOH_RULES), mode.toWeb()),
                useProxy = true,
                preferredUpstreamId = preset?.id,
                customUpstreams = custom,
                // 用户显式填的自定义地址：只用它，不再和内置上游混跑
                customOnly = custom.isNotEmpty()
            )
        )
    }

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
         * 规则（与设置页的校验保持一致）：
         * - 必须 https://
         * - 必须有主机名，且带非空路径（`/dns-query` 这类端点；只有 host 的地址大概率填错）
         * 不合法返回空表 → 退化为只用内置上游，不会把引擎带崩。
         */
        fun buildCustomUpstreams(url: String): List<cn.olfact.web.doh.DohUpstream> {
            val clean = url.trim()
            if (!clean.startsWith("https://", ignoreCase = true)) return emptyList()
            val rest = clean.substringAfter("://")
            val host = rest.substringBefore('/').substringBefore(':')
            val path = rest.substringAfter('/', "")
            if (host.isBlank() || path.isBlank()) return emptyList()
            val label = "自定义 · $host"
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
        val custom = customUpstreams(requested)
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
