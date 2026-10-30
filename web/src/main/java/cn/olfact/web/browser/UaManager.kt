package cn.olfact.web.browser

import android.content.Context
import android.webkit.WebSettings

/**
 * UA 策略：移动 / 桌面 / 自定义 / 按域名。
 */
class UaManager(
    private val mobileUa: String,
    private val siteConfigs: (String) -> SiteConfig? = { null }
) {

    fun uaFor(host: String?, mode: UaMode, customUa: String? = null): String {
        val siteUa = host?.let { siteConfigs(it)?.customUa }
        if (!siteUa.isNullOrBlank()) return siteUa
        return when (mode) {
            UaMode.MOBILE -> mobileUa
            UaMode.DESKTOP -> DESKTOP_UA
            UaMode.CUSTOM -> customUa?.takeIf { it.isNotBlank() } ?: mobileUa
            UaMode.PER_SITE -> mobileUa
        }
    }

    companion object {
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        fun systemMobileUa(context: Context): String =
            runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault(DESKTOP_UA)
    }
}
