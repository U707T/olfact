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

    /**
     * 在页面里执行一段 JS 并把结果回传（AI 总结抓正文、划词翻译取选区用）。
     *
     * 默认空实现：兜底渲染器可以不支持；实现方返回的是 `evaluateJavascript` 的原始串
     * （JSON 字符串字面量，调用方负责去引号）。
     */
    fun evaluateJavascript(script: String, callback: (String) -> Unit) {
        callback("")
    }

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

    /** 主文档加载失败（DNS/超时/HTTP 错误）。默认空实现，兜底渲染器可以不支持。 */
    fun onError(code: Int, description: String, url: String) {}

    /**
     * 带坐标的长按（原版菜单锚在手指位置）。
     * 默认转调 [onLongPress]，不支持坐标的渲染器仍然可用。
     */
    fun onLongPressAt(url: String?, title: String?, x: Float, y: Float) {
        onLongPress(url, title)
    }
}
