package cn.olfact.core.adblock

import kotlinx.serialization.Serializable

/**
 * 一条订阅。`builtinAsset` 非空表示规则文本打到 APK 里（首次启动直接可用），
 * 否则从 [url] 拉取。
 */
@Serializable
data class Subscription(
    val id: String,
    val title: String,
    val url: String = "",
    val enabled: Boolean = true,
    val builtinAsset: String? = null,
    val lastUpdated: Long = 0L,
    val etag: String? = null,
    val lastModified: String? = null,
    val ruleCount: Int = 0,
    val lastError: String? = null,
    val order: Int = 0
) {
    val isBuiltin: Boolean get() = !builtinAsset.isNullOrEmpty()

    val displayRuleCount: String
        get() = if (ruleCount >= 10_000) "${ruleCount / 1000}k" else ruleCount.toString()
}

object SubscriptionCatalog {

    const val ASSET_DIR = "adblock"

    /**
     * 内置两条 + 两条常用在线清单。
     * 在线清单默认关闭：首启不联网、不占流量；用户在设置页勾选后才下载。
     */
    fun builtins(): List<Subscription> = listOf(
        Subscription(
            id = "builtin-ublite",
            title = "uBO Lite 内置清单",
            enabled = true,
            builtinAsset = "builtin-ublite.txt",
            order = 0
        ),
        Subscription(
            id = "builtin-abp-cn",
            title = "ABP 中文基础清单（内置节选）",
            enabled = true,
            builtinAsset = "builtin-abp-cn.txt",
            order = 1
        ),
        Subscription(
            id = "easylist-china",
            title = "EasyList China",
            url = "https://easylist-downloads.adblockplus.org/easylistchina.txt",
            enabled = false,
            order = 2
        ),
        Subscription(
            id = "adguard-cn",
            title = "AdGuard 中文过滤器",
            url = "https://filters.adtidy.org/extension/ublock/filters/224.txt",
            enabled = false,
            order = 3
        )
    )
}
