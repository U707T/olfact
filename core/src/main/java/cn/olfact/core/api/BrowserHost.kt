package cn.olfact.core.api

/**
 * 浏览器壳对外能力。B2 的 `BrowserFragment` 实现，B5 的界面 / 外部入口调用。
 *
 * 只暴露"别的模块需要浏览器做什么"，不要把 WebView 细节放进来。
 */
interface BrowserHost {
    /** 新开标签并加载 [url]；传 null 表示打开空白新标签页。 */
    fun newTab(url: String?)

    /** 当前标签的地址，无标签时返回 null。 */
    fun currentUrl(): String?

    /** 跳到设置页（由 app 层路由）。 */
    fun openSettings()
}
