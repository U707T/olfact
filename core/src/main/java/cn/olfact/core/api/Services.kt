package cn.olfact.core.api

/**
 * 跨模块服务注册表。
 *
 * 为什么不是 DI 框架：工程只有 6 个模块、依赖方向固定，Hilt 的构建成本与体积收益不匹配。
 * 约定：实现方在 `Application.onCreate()` 里注册，界面层只读。
 *
 * 任何一项为 null 时，界面必须降级而不是崩溃（B5 的 UI 先行约定）。
 */
object OlfactServices {

    @Volatile var settings: SettingsRepository? = null
    @Volatile var browserHost: BrowserHost? = null
    @Volatile var browserSurface: BrowserSurface? = null
    @Volatile var sniffer: MediaSniffer? = null
    @Volatile var adBlock: AdBlockHook? = null
    @Volatile var adBlockController: AdBlockController? = null
    @Volatile var scripts: ScriptSource? = null
    @Volatile var scriptStore: ScriptStore? = null
    @Volatile var doh: DohManager? = null
    @Volatile var dohController: DohController? = null
    @Volatile var aiProviders: ProviderStore? = null
    @Volatile var downloads: DownloadCenter? = null

    /** 已注册模块的简报，供「关于」页与日志使用。 */
    fun modules(): List<Pair<String, Boolean>> = listOf(
        "web" to (browserSurface != null || browserHost != null),
        "player" to (sniffer != null),
        "download" to (downloads != null),
        "ai" to (aiProviders != null),
        "adblock" to (adBlockController != null),
        "script" to (scriptStore != null),
        "doh" to (doh != null)
    )
}
