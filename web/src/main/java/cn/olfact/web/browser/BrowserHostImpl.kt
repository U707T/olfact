package cn.olfact.web.browser

import cn.olfact.core.api.BrowserHost

/**
 * 对界面层的实现：`BrowserHost` 只有三个方法，刻意保持窄。
 */
class BrowserHostImpl(
    private val tabs: TabManager,
    private val onOpenSettings: () -> Unit = {}
) : BrowserHost {

    override fun newTab(url: String?) {
        tabs.open(url)
    }

    override fun currentUrl(): String? =
        tabs.activeTab()?.let { tab -> tab.view?.url ?: tab.url }

    override fun openSettings() {
        onOpenSettings()
    }
}
