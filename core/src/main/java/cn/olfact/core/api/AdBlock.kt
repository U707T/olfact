package cn.olfact.core.api

/**
 * 广告拦截钩子（契约见 `docs/tasks/README.md` §3）。
 *
 * 实现：`cn.olfact.core.adblock.AdBlockManager`。
 * 调用方（B2）在 `WebViewClient.shouldInterceptRequest` 里逐请求询问；
 * 返回 true 时请再调一次 [recordBlocked] 让计数与实际拦截一致。
 *
 * [B4 临时落地] 本文件与契约逐字一致；B1 若产出同签名版本，直接覆盖此文件即可，实现类无需改动。
 */
interface AdBlockHook {
    /** 是否拦截该请求。[pageHost] 是当前页面 host（无 Referer 时由调用方传入）。 */
    fun shouldBlock(url: String, pageHost: String): Boolean

    /** 该页面需要注入的隐藏元素 CSS；无规则时返回空串。 */
    fun cosmeticCss(pageHost: String): String

    /** 记录一次实际拦截，用于设置页计数。 */
    fun recordBlocked()
}
