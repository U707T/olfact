package cn.olfact.web.browser

/**
 * 浏览事件出口。历史记录由 app 层落库（无痕模式不会收到回调）。
 */
interface BrowserEvents {

    fun onPageVisited(url: String, title: String)

    fun onTitleChanged(tabId: String, title: String)

    fun onProgress(tabId: String, progress: Int)

    companion object {
        val NONE: BrowserEvents = object : BrowserEvents {
            override fun onPageVisited(url: String, title: String) = Unit
            override fun onTitleChanged(tabId: String, title: String) = Unit
            override fun onProgress(tabId: String, progress: Int) = Unit
        }
    }
}
