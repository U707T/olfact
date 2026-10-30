package cn.olfact.core.api

/**
 * 浏览器宿主。B2 实现，B5 通过它挂载界面。
 *
 * 注意：签名已冻结，B5 只调用不实现。
 */
interface BrowserHost {
    /** 新开标签页；[url] 为 null 时打开空白页。 */
    fun newTab(url: String?)

    /** 当前标签页地址，无标签时为 null。 */
    fun currentUrl(): String?

    /** 跳到设置。 */
    fun openSettings()
}

/**
 * 宿主可选的扩展能力：由 B2 实现并在 [OlfactServices] 注册。
 *
 * B5 用它把「当前标签页」的标题/进度/前进后退状态画进地址栏；
 * 未注册时界面回落到本地最小渲染器，不影响其它功能。
 */
interface BrowserSurface {
    /** 一个可挂进 Fragment 的视图（WebView 容器）。 */
    fun createView(host: BrowserSurfaceHost): android.view.View

    fun loadUrl(url: String)
    fun reload()
    fun stopLoading()
    fun canGoBack(): Boolean
    fun goBack()
    fun canGoForward(): Boolean
    fun goForward()
    fun title(): String?
    fun url(): String?
    fun destroy()

    // 页内查找（UI 层只画工具栏，匹配逻辑留在渲染器里）
    fun findAll(text: String)
    fun findNext(forward: Boolean)
    fun clearFind()
}

/** 渲染器向 UI 回报的状态。 */
interface BrowserSurfaceHost {
    fun onProgress(progress: Int)
    fun onTitle(title: String?)
    fun onUrl(url: String)
    fun onPageStarted(url: String)
    fun onPageFinished(url: String)
    fun onLongPress(url: String?, title: String?)
}
