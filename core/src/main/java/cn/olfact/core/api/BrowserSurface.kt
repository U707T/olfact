package cn.olfact.core.api

/**
 * 可挂进 Fragment 的渲染器视图。B2 的 `web` 模块负责实现，B5 的界面只依赖本接口。
 *
 * 合并说明：B1 的 `BrowserHost.kt` 只保留三方法窄接口（签名冻结），
 * 扩展能力单独放在本文件，避免动已冻结契约。
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

/** 每开一个标签页要一份新的 [BrowserSurface]，所以注册的是工厂而不是单例。 */
fun interface BrowserSurfaceFactory {
    fun create(): BrowserSurface
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
