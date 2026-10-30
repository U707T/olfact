package cn.olfact.core.api

/**
 * 广告拦截钩子。B4 实现（`core/adblock`），B2 在请求链路与页面注入处调用。
 *
 * 约定：
 * - [shouldBlock] 必须够快（主线程同步调用场景），实现内部自己缓存规则。
 * - [cosmeticCss] 返回可直接 `insertCSS` 的样式串，无命中返回空串。
 */
interface AdBlockHook {
    /**
     * @param url 待判定资源地址（绝对 URL）
     * @param pageHost 当前页面 host（小写、不含端口），用于 $domain= 规则与白名单
     */
    fun shouldBlock(url: String, pageHost: String): Boolean

    /** 元素隐藏规则，按页面 host 生成 CSS。 */
    fun cosmeticCss(pageHost: String): String

    /** 计数 +1（拦截成功后调用，供设置页统计）。 */
    fun recordBlocked()
}
