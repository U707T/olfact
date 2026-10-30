package cn.olfact.core.api

/**
 * 脚本来源。B4 实现（`core/script`），B2 负责用
 * `WebViewCompat.addDocumentStartJavaScript` 注入。
 *
 * 只返回脚本文本，不关心 GM_* 桥接——那是 B4 的 JsBridge 的事。
 */
interface ScriptSource {
    /**
     * @param url 当前页面地址
     * @return 命中的 UserScript 源码（已包好 IIFE），按顺序注入
     */
    fun documentStartScripts(url: String): List<String>
}
