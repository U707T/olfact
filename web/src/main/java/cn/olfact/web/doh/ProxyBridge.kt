package cn.olfact.web.doh

import android.os.Build
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 自检成功后才设置 WebView 代理；迟到的异步回调不能覆盖后一次 attach/detach。 */
class ProxyBridge(
    private val executor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "olfact-proxy-bridge").apply { isDaemon = true }
    },
    private val threshold: Int = 3,
    private val onCircuitOpen: (String) -> Unit = {},
    private val onStateChanged: (Boolean) -> Unit = {}
) {
    private val attached = AtomicBoolean(false)
    private val failures = AtomicInteger(0)
    private val lock = Any()
    private var revision = 0L

    fun isSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)

    fun attach(port: Int, selfCheck: () -> Boolean): Boolean {
        val attempt = synchronized(lock) { ++revision }
        if (!isSupported()) {
            onCircuitOpen("当前 WebView 不支持 ProxyController（需 Android 10+）")
            return false
        }
        if (port <= 0) {
            onCircuitOpen("隧道端口无效")
            return false
        }
        if (!selfCheck()) {
            onCircuitOpen("代理自检失败，保持直连")
            return false
        }
        return synchronized(lock) {
            if (attempt != revision) return@synchronized false
            try {
                val config = ProxyConfig.Builder()
                    .addProxyRule("127.0.0.1:$port")
                    .addBypassRule("127.0.0.1")
                    .addBypassRule("localhost")
                    .addBypassRule("<local>")
                    .build()
                ProxyController.getInstance().setProxyOverride(config, executor) {
                    synchronized(lock) {
                        if (attempt == revision) {
                            attached.set(true)
                            failures.set(0)
                            onStateChanged(true)
                        }
                    }
                }
                true
            } catch (e: Exception) {
                onCircuitOpen("setProxyOverride 失败：${e.message}")
                false
            }
        }
    }

    fun detach() {
        synchronized(lock) {
            val attempt = ++revision
            attached.set(false)
            failures.set(0)
            if (!isSupported()) return
            runCatching {
                ProxyController.getInstance().clearProxyOverride(executor) {
                    synchronized(lock) {
                        if (attempt == revision) onStateChanged(false)
                    }
                }
            }
        }
    }

    fun isAttached(): Boolean = attached.get()
    fun consecutiveFailures(): Int = failures.get()

    fun noteFailure(reason: String) {
        if (!attached.get()) return
        if (failures.incrementAndGet() >= threshold) {
            detach()
            onCircuitOpen("连续 $threshold 次代理失败，已熔断：$reason")
        }
    }

    fun noteSuccess() { failures.set(0) }
}
