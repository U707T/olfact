package cn.olfact.core.script

import android.webkit.JavascriptInterface

/**
 * 注入到 WebView 的 Java 对象：`addJavascriptInterface(GmJsInterface(bridge), "__olfactGmBridge")`。
 * 方法体只有一行转发，方便在单测里绕开 android.jar 桩直接测 [GmBridge]。
 */
class GmJsInterface(private val bridge: GmBridge) {

    @JavascriptInterface
    fun dispatch(scriptId: String, callId: String, method: String, argsJson: String): String =
        bridge.dispatch(scriptId, callId, method, argsJson)
}
