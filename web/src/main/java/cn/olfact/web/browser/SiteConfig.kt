package cn.olfact.web.browser

/**
 * 按域名的站点配置。字段为 null 表示“跟随全局”。
 */
data class SiteConfig(
    val host: String,
    val javaScriptEnabled: Boolean? = null,
    val imagesEnabled: Boolean? = null,
    val nightMode: Boolean? = null,
    val desktopMode: Boolean? = null,
    val customUa: String? = null,
    /** 该域名是否强制走 DoH（地址栏长按写入） */
    val dohEnabled: Boolean? = null
) {

    fun mergeInto(base: BrowserConfig): BrowserConfig = base.copy(
        javaScriptEnabled = javaScriptEnabled ?: base.javaScriptEnabled,
        imagesEnabled = imagesEnabled ?: base.imagesEnabled,
        nightMode = nightMode ?: base.nightMode,
        uaMode = if (desktopMode == true) UaMode.DESKTOP else base.uaMode,
        customUa = customUa ?: base.customUa
    )

    companion object {
        fun normalizeHost(host: String): String =
            host.trim().lowercase()
                .removePrefix("http://")
                .removePrefix("https://")
                .substringBefore('/')
                .substringBefore(':')
                .trim('.')
    }
}
