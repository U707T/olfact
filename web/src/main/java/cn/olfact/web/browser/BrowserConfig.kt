package cn.olfact.web.browser

/** UA 策略。PER_SITE 表示按域名表覆盖，表里没有就回落到 MOBILE。 */
enum class UaMode { MOBILE, DESKTOP, CUSTOM, PER_SITE }

/**
 * 一个标签页的浏览配置。由设置页 + 站点配置合成后交给 [BrowserView.applyConfig]。
 */
data class BrowserConfig(
    val uaMode: UaMode = UaMode.MOBILE,
    val customUa: String? = null,
    val imagesEnabled: Boolean = true,
    val javaScriptEnabled: Boolean = true,
    val nightMode: Boolean = false,
    val textZoom: Int = 100,
    val incognito: Boolean = false,
    val searchTemplate: String = "https://www.bing.com/search?q=%s",
    /** true 时禁用第三方 Cookie（无痕默认开） */
    val blockThirdPartyCookies: Boolean = false
)
