package androidx.webkit

import android.webkit.WebSettings
import android.webkit.WebView
import java.util.concurrent.Executor

class ScriptHandler { fun remove() {} }
object WebSettingsCompat { fun setAlgorithmicDarkeningAllowed(settings: WebSettings, enabled: Boolean) {} }
object WebViewCompat {
    fun addDocumentStartJavaScript(view: WebView, script: String, origins: Set<String>) = ScriptHandler()
}
object WebViewFeature {
    const val DOCUMENT_START_SCRIPT = "document-start"
    const val ALGORITHMIC_DARKENING = "darkening"
    const val PROXY_OVERRIDE = "proxy"
    fun isFeatureSupported(feature: String) = feature != ALGORITHMIC_DARKENING
}
class ProxyConfig {
    class Builder {
        fun addProxyRule(rule: String) = this
        fun addBypassRule(rule: String) = this
        fun build() = ProxyConfig()
    }
}
class ProxyController private constructor() {
    val callbacks = ArrayList<Runnable>()
    var setCalls = 0
    fun setProxyOverride(config: ProxyConfig, executor: Executor, listener: Runnable) { setCalls++; callbacks += listener }
    fun clearProxyOverride(executor: Executor, listener: Runnable) { callbacks += listener }
    fun reset() { callbacks.clear(); setCalls = 0 }
    companion object { private val instance = ProxyController(); fun getInstance() = instance }
}
