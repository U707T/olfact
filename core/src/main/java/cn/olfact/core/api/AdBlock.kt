package cn.olfact.core.api

/**
 * 广告拦截钩子。由 B4 提供实现，B2 在 WebView 请求链路上调用。
 */
interface AdBlockHook {
    /** 是否拦截该请求。pageHost 为发起页面的主机名，用于第一方豁免。 */
    fun shouldBlock(url: String, pageHost: String): Boolean

    /** 该页面需要注入的 cosmetic CSS（隐藏空位、反插桩），无内容时返回空串。 */
    fun cosmeticCss(pageHost: String): String

    /** 命中一次拦截，供统计使用。 */
    fun recordBlocked()
}
