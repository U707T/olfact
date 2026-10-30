package cn.olfact.core.api

/**
 * 浏览内核对外暴露的最小入口，界面层（B5）只通过它操作标签页。
 */
interface BrowserHost {
    fun newTab(url: String?)
    fun currentUrl(): String?
    fun openSettings()
}
