package cn.olfact.web.tools

import cn.olfact.web.browser.BrowserChromeClient
import org.junit.Assert.*
import org.junit.Test

/**
 * HTML5 custom-view lifetime via the real [BrowserChromeClient] and the test-only
 * Android/WebKit stubs. 验收重点：退出只执行一次、被拒绝时仍完成 WebView 回调
 * （否则页面一直等 custom view，播放器黑屏）、第二条请求不会抢走第一条的回调。
 */
class CustomViewSessionTest {

    private class Harness {
        val calls = mutableListOf<Int>()
        var dismissed = 0
        var accept = true
        private var pendingExit: (() -> Unit)? = null

        val client: BrowserChromeClient

        init {
            client = BrowserChromeClient(
                "tab",
                onShowCustomView = { _, onExit ->
                    if (accept) { pendingExit = onExit; true } else false
                },
                onHideCustomView = { dismissed++ }
            )
        }

        fun callback() = object : android.webkit.WebChromeClient.CustomViewCallback {
            override fun onCustomViewHidden() { calls += 1 }
        }

        /** 模拟页面自己退出全屏（awaiting the exit captured by the presented host）。 */
        fun exitFromPage(): Boolean {
            val exit = pendingExit ?: return false
            return runCatching { exit(); true }.getOrDefault(false)
        }
    }

    @Test fun `presented custom view exits exactly once and completes WebView callback`() {
        val h = Harness()
        h.client.onShowCustomView(android.view.View(), h.callback())
        assertTrue(h.client.hideCustomView())
        assertFalse("重复退出必须是空操作", h.client.hideCustomView())
        assertEquals(1, h.calls.size)
        assertEquals(1, h.dismissed)
    }

    @Test fun `page-initiated exit also completes the callback once`() {
        val h = Harness()
        h.client.onShowCustomView(android.view.View(), h.callback())
        assertTrue(h.exitFromPage())
        assertFalse(h.client.hideCustomView())
        assertEquals(1, h.calls.size)
    }

    @Test fun `rejected presentation still completes the WebView callback`() {
        val h = Harness().apply { accept = false }
        h.client.onShowCustomView(android.view.View(), h.callback())
        assertEquals(1, h.calls.size)
        assertFalse(h.client.hideCustomView())
    }

    @Test fun `null custom view immediately completes callback`() {
        val h = Harness()
        h.client.onShowCustomView(null, h.callback())
        assertEquals(1, h.calls.size)
        assertFalse(h.client.hideCustomView())
    }

    @Test fun `second request while active does not steal the first callback`() {
        val h = Harness()
        h.client.onShowCustomView(android.view.View(), h.callback())
        h.client.onShowCustomView(android.view.View(), h.callback())
        // 第二条被立即结束，第一条保持所有权。
        assertEquals(1, h.calls.size)
        assertTrue(h.client.hideCustomView())
        assertEquals(2, h.calls.size)
    }

    @Test fun `throwing host is treated as rejection and does not lock the session`() {
        var calls = 0
        val client = BrowserChromeClient(
            "tab",
            onShowCustomView = { _, _ -> throw IllegalStateException("host gone") },
            onHideCustomView = {}
        )
        client.onShowCustomView(android.view.View(), object : android.webkit.WebChromeClient.CustomViewCallback {
            override fun onCustomViewHidden() { calls++ }
        })
        assertEquals(1, calls)
        assertFalse(client.hideCustomView())
    }

    @Test fun `legacy orientation entrypoint keeps the same single ownership`() {
        val h = Harness()
        @Suppress("DEPRECATION")
        h.client.onShowCustomView(android.view.View(), 6, h.callback())
        assertTrue(h.client.hideCustomView())
        assertEquals(1, h.calls.size)
    }
}
