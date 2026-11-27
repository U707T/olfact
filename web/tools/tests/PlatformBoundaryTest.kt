package cn.olfact.web.tools

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.webkit.ProxyController
import cn.olfact.core.api.AdBlockHook
import cn.olfact.core.api.MediaItem
import cn.olfact.core.api.MediaSniffer
import cn.olfact.web.browser.BrowserClient
import cn.olfact.web.browser.BrowserConfig
import cn.olfact.web.browser.BrowserView
import cn.olfact.web.doh.DohConfig
import cn.olfact.web.doh.DohManagerImpl
import cn.olfact.web.doh.DohMode
import cn.olfact.web.doh.ProxyBridge
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Runs only in test-web.ps1 with explicit Android/WebKit stubs, never in Android Gradle tests. */
class PlatformBoundaryTest {
    private val controller get() = ProxyController.getInstance()
    @Before fun reset() = controller.reset()
    private fun request(url: String, main: Boolean = false, headers: Map<String, String> = emptyMap()) = object : WebResourceRequest {
        override val url = Uri.parse(url)
        override val isForMainFrame = main
        override val requestHeaders = headers
    }

    @Test fun `late attach callback cannot revive detached bridge`() {
        val bridge = ProxyBridge(executor = Executor { it.run() })
        assertTrue(bridge.attach(12345) { true })
        bridge.detach()
        controller.callbacks[0].run()
        assertFalse(bridge.isAttached())
    }

    @Test fun `detach during self check prevents later proxy command`() {
        val bridge = ProxyBridge(executor = Executor { it.run() })
        assertFalse(bridge.attach(12345) { bridge.detach(); true })
        assertEquals(0, controller.setCalls)
    }

    @Test fun `late clear callback cannot clear a newer attachment`() {
        val bridge = ProxyBridge(executor = Executor { it.run() })
        bridge.detach()
        assertTrue(bridge.attach(12345) { true })
        controller.callbacks[1].run()
        controller.callbacks[0].run()
        assertTrue(bridge.isAttached())
    }

    @Test fun `media interception reads UI snapshot and passes actual request headers`() {
        val found = AtomicReference<MediaItem>()
        val sniffer = object : MediaSniffer {
            override fun onMediaDetected(item: MediaItem) { found.set(item) }
        }
        val client = BrowserClient("tab", { BrowserConfig() }, sniffer = sniffer)
        val view = WebView(Context())
        view.loadUrl("https://page.example/watch")
        client.onPageStarted(view, view.url!!, null)
        val failure = AtomicReference<Throwable>()
        thread {
            try { client.shouldInterceptRequest(view, request("https://cdn.example/movie.m3u8", headers = mapOf("Authorization" to "test"))) }
            catch (error: Throwable) { failure.set(error) }
        }.join()
        assertNull(failure.get())
        assertEquals("https://page.example/watch", found.get().pageUrl)
        assertEquals("stub-agent", found.get().headers["User-Agent"])
        assertEquals("test", found.get().headers["Authorization"])
    }

    @Test fun `same media on two pages is reported with each page context`() {
        val items = mutableListOf<MediaItem>()
        val client = BrowserClient("tab", { BrowserConfig() }, sniffer = object : MediaSniffer {
            override fun onMediaDetected(item: MediaItem) { items += item }
        })
        val view = WebView(Context())
        val media = "https://cdn.example/song.M4A?token=one"
        for (i in 1..2) {
            val page = "https://page.example/watch/$i"
            client.onPageStarted(view, page, null)
            client.updatePageTitle("Page $i")
            client.shouldInterceptRequest(view, request(media, headers = mapOf(
                "referer" to "https://page.example/", "user-agent" to "actual-agent", "Range" to "bytes=0-1"
            )))
        }
        assertEquals(listOf("https://page.example/watch/1", "https://page.example/watch/2"), items.map { it.pageUrl })
        assertEquals(listOf("Page 1", "Page 2"), items.map { it.pageTitle })
        assertTrue(items.all { it.type == cn.olfact.core.api.MediaType.AUDIO })
        assertEquals(mapOf("Referer" to "https://page.example/", "User-Agent" to "actual-agent"), items.last().headers)
    }

    @Test fun `adblock gets page host rather than resource host`() {
        val host = AtomicReference<String>()
        val hook = object : AdBlockHook {
            override fun shouldBlock(url: String, pageHost: String): Boolean { host.set(pageHost); return false }
            override fun cosmeticCss(pageHost: String) = ""
            override fun recordBlocked() {}
        }
        val view = WebView(Context())
        val client = BrowserClient("tab", { BrowserConfig() }, adblock = hook)
        client.onPageStarted(view, "https://page.example/watch", null)
        client.shouldInterceptRequest(view, request("https://cdn.example/ads.js"))
        assertEquals("page.example", host.get())
    }

    @Test fun `external iframe is consumed without launching an app`() {
        var launches = 0
        val client = BrowserClient("tab", { BrowserConfig() }, onExternalScheme = { launches++; false })
        val view = WebView(Context())
        assertTrue(client.shouldOverrideUrlLoading(view, request("market://details?id=test")))
        assertEquals(0, launches)
        assertTrue(client.shouldOverrideUrlLoading(view, request("market://details?id=test", true)))
        assertEquals(1, launches)
    }

    @Test fun `night and cosmetic CSS use independent DOM slots`() {
        val view = BrowserView(Context())
        view.setNightMode(true)
        view.injectCss(".ad{display:none}")
        view.setNightMode(false)
        assertTrue(view.evaluatedScripts[0].contains("olfact-night"))
        assertTrue(view.evaluatedScripts[1].contains("olfact-cosmetic"))
        assertTrue(view.evaluatedScripts[2].contains("olfact-night"))
        assertFalse(view.evaluatedScripts[2].contains("olfact-cosmetic"))
    }

    @Test fun `enable for host removes exclusion instead of adding it`() {
        val manager = DohManagerImpl(Context())
        manager.updateRules(cn.olfact.web.doh.DnsRules.parse("+.example.com", DohMode.EXCLUDE_LISTED))
        assertFalse(manager.rules().useDoh("example.com"))
        manager.enableForHost("example.com")
        assertTrue(manager.rules().useDoh("example.com"))
    }

    @Test fun `manager applies mode and clears dynamic DNS entry on stop`() {
        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + dispatcher)
        val manager = DohManagerImpl(Context(), scope)
        try {
            manager.start(DohConfig(mode = DohMode.ONLY_LISTED, useProxy = false))
            assertEquals(DohMode.ONLY_LISTED, manager.rules().mode)
            assertNotNull(manager.appDns())
            manager.stop()
            assertFalse(manager.status().running)
            assertNull(manager.appDns())
            assertNull(manager.tunnelStats())
            assertEquals(0, controller.setCalls)
        } finally {
            // 不调度已排队的上游竞速；真实 DoH 只在显式 LiveDohTest 中运行。
            scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        }
    }

    @Test fun `http error page remains available while network failures still show browser error`() {
        val errors = mutableListOf<Pair<Int, String>>()
        val client = BrowserClient("tab", { BrowserConfig() }, onError = { code, message, _ ->
            errors += code to message
        })
        val view = WebView(Context())
        val body = java.io.ByteArrayInputStream("<button>retry</button>".toByteArray())
        val response = android.webkit.WebResourceResponse("text/html", "utf-8", body).apply {
            statusCode = 403
        }

        client.onReceivedHttpError(view, request("https://site.example/blocked", main = true), response)
        assertTrue("HTTP body must not be covered by the full-page network error UI", errors.isEmpty())

        client.onReceivedError(view, request("https://site.example/offline", main = true),
            android.webkit.WebResourceError(-2, "offline"))
        assertEquals(listOf(-2 to "offline"), errors)
    }
}
