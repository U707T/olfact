package cn.olfact.web.doh

import android.os.Build
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 把本地隧道挂到 WebView 上（`ProxyController`，Android Q+）。
 *
 * 两条硬规则（TECH-PLAN §4.5-6）：
 * 1. 起代理前自检，自检不过绝不 `setProxyOverride`；
 * 2. 连续失败到阈值就 `clearProxyOverride`，宁可退回直连也不白屏。
 */
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

    fun isSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)

    /**
     * @param selfCheck 起代理前的自检，返回 false 则完全不动 WebView 配置
     * @return 是否成功挂上
     */
    fun attach(port: Int, selfCheck: () -> Boolean): Boolean {
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
        return try {
            val config = ProxyConfig.Builder()
                .addProxyRule("127.0.0.1:$port")
                .addBypassRule("127.0.0.1")
                .addBypassRule("localhost")
                .addBypassRule("<local>")
                .build()
            ProxyController.getInstance().setProxyOverride(config, executor) {
                attached.set(true)
                failures.set(0)
                onStateChanged(true)
            }
            true
        } catch (e: Exception) {
            onCircuitOpen("setProxyOverride 失败：${e.message}")
            false
        }
    }

    fun detach() {
        if (!isSupported()) {
            attached.set(false)
            return
        }
        runCatching {
            ProxyController.getInstance().clearProxyOverride(executor) {
                attached.set(false)
                onStateChanged(false)
            }
        }
        attached.set(false)
    }

    fun isAttached(): Boolean = attached.get()

    fun consecutiveFailures(): Int = failures.get()

    /** 隧道层报错时调用；到阈值直接熔断。 */
    fun noteFailure(reason: String) {
        if (!attached.get()) return
        if (failures.incrementAndGet() >= threshold) {
            failures.set(0)
            detach()
            onCircuitOpen("连续 $threshold 次代理失败，已熔断：$reason")
        }
    }

    fun noteSuccess() {
        failures.set(0)
    }
}
