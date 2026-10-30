package cn.olfact.core.script

import cn.olfact.core.b4.B4Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * GM_* 桥：**不引 QuickJS**（TECH-PLAN §7 的决定）。
 *
 * 注入顺序：先注 [prelude]（定义 GM_* 全局与 `__olfactGmBridge.dispatch` 调用点），
 * 再注用户脚本体，两者在同一个 IIFE 里，GM_* 是局部变量，多脚本互不覆盖。
 * 同步方法立即返回字符串；`GM_xmlhttpRequest` 走线程池，完成后用 [emit] 把结果打回页面。
 *
 * 安全边界：GM_xmlhttpRequest 允许跨域（油猴语义），风险由用户安装脚本时承担。
 */
class GmBridge(
    private val values: GmValueStore,
    private val http: OkHttpClient,
    private val emit: (String) -> Unit,
    private val logger: (String) -> Unit = {}
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val executor: ExecutorService = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "olfact-gm-xhr").apply { isDaemon = true }
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    // ------------------------------------------------------------- 注入代码

    fun prelude(script: UserScript): String {
        val meta = buildJsonObject {
            put("name", JsonPrimitive(script.name))
            put("namespace", JsonPrimitive(script.namespace))
            put("version", JsonPrimitive(script.version))
            put("description", JsonPrimitive(script.description))
            put("author", JsonPrimitive(script.author))
            put("matches", JsonArray(script.matches.map { JsonPrimitive(it) }))
            put("grants", JsonArray(script.grants.map { JsonPrimitive(it) }))
            put("runAt", JsonPrimitive(script.runAt.metaName))
        }.toString()

        return PRELUDE
            .replace("%SCRIPT_ID%", jsString(script.id))
            .replace("%SCRIPT_META%", meta)
    }

    /** prelude（未闭合的 IIFE）+ 脚本体 + 收尾。 */
    fun wrap(script: UserScript, body: String): String = buildString(body.length + 8192) {
        append(prelude(script))
        append("\ntry {\n")
        append(body)
        append("\n} catch (__olfactErr) { try { __call(\"log\", [\"[olfact] 脚本异常: \" + (__olfactErr && __olfactErr.stack ? __olfactErr.stack : __olfactErr)]); } catch (e) {} }\n")
        append("})();\n")
    }

    // --------------------------------------------------------------- 分发

    /**
     * 供 `@JavascriptInterface` 调用。返回的字符串会原样成为 JS 字符串（JS 侧自行 JSON.parse）。
     */
    fun dispatch(scriptId: String, callId: String, method: String, argsJson: String): String {
        val args: JsonArray = runCatching { json.parseToJsonElement(argsJson).jsonArray }
            .getOrElse { JsonArray(emptyList()) }
        return when (method) {
            "get" -> values.get(scriptId, args.stringArg(0))
            "set" -> {
                values.set(scriptId, args.stringArg(0), args.rawArg(1) ?: "null")
                ""
            }
            "delete" -> {
                values.delete(scriptId, args.stringArg(0))
                ""
            }
            "keys" -> JsonArray(values.keys(scriptId).map { JsonPrimitive(it) }).toString()
            "log" -> {
                val msg = args.stringArg(0)
                logger(msg)
                B4Log.d("GmBridge", msg)
                ""
            }
            "xhr" -> {
                startXhr(callId, args.rawArg(0) ?: "{}")
                ""
            }
            else -> ""
        }
    }

    private fun startXhr(callId: String, detailsJson: String) {
        executor.execute {
            val payload = try {
                executeXhr(detailsJson)
            } catch (t: Throwable) {
                B4Log.w("GmBridge", "GM_xmlhttpRequest 失败", t)
                """{"error":${jsString(t.message ?: t.javaClass.simpleName)}}"""
            }
            runCatching { resolve(callId, payload) }
                .onFailure { B4Log.w("GmBridge", "回传 GM_xhr 结果失败", it) }
        }
    }

    private fun executeXhr(detailsJson: String): String {
        val d = runCatching { json.parseToJsonElement(detailsJson).jsonObject }.getOrNull()
            ?: throw IllegalArgumentException("details 不是对象")
        val url = d["url"]?.jsonPrimitive?.contentOrNull ?: throw IllegalArgumentException("缺少 url")
        val method = (d["method"]?.jsonPrimitive?.contentOrNull ?: "GET").uppercase()
        val data = d["data"]?.jsonPrimitive?.contentOrNull
        val headers = d["headers"] as? JsonObject

        val builder = Request.Builder().url(url)
        headers?.forEach { (k, v) ->
            (v as? JsonPrimitive)?.contentOrNull?.let { builder.header(k, it) }
        }
        val body = when {
            data == null -> null
            method == "GET" || method == "HEAD" -> null
            else -> data.toRequestBody("application/x-www-form-urlencoded".toMediaType())
        }
        builder.method(method, body)

        val timeout = d["timeout"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        val client = if (timeout != null && timeout > 0) {
            http.newBuilder().callTimeout(timeout, TimeUnit.MILLISECONDS).build()
        } else {
            http
        }

        client.newCall(builder.build()).execute().use { resp ->
            var text = resp.body.string()
            if (text.length > MAX_RESPONSE_CHARS) text = text.substring(0, MAX_RESPONSE_CHARS)
            val headerText = resp.headers.joinToString("\n") { "${it.first}: ${it.second}" }
            return buildString(text.length + 256) {
                append("{\"status\":").append(resp.code)
                append(",\"statusText\":").append(jsString(resp.message))
                append(",\"finalUrl\":").append(jsString(resp.request.url.toString()))
                append(",\"responseHeaders\":").append(jsString(headerText))
                append(",\"responseText\":").append(jsString(text))
                append('}')
            }
        }
    }

    private fun resolve(callId: String, payloadJson: String) {
        emit("window.__olfactGmResolve&&window.__olfactGmResolve(${jsString(callId)},${jsString(payloadJson)});")
    }

    companion object {
        private const val MAX_RESPONSE_CHARS = 2 * 1024 * 1024

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        /** JSON 字符串字面量（带引号 + 转义），用于拼 JS。 */
        fun jsString(text: String): String {
            val sb = StringBuilder(text.length + 16)
            sb.append('"')
            for (c in text) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\u2028' -> sb.append("\\u2028")
                    '\u2029' -> sb.append("\\u2029")
                    else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
                }
            }
            sb.append('"')
            return sb.toString()
        }
    }
}

private fun JsonArray.stringArg(index: Int): String =
    (getOrNull(index) as? JsonPrimitive)?.contentOrNull ?: ""

private fun JsonArray.rawArg(index: Int): String? = getOrNull(index)?.toString()

/**
 * 页面侧前置代码。**故意不闭合 IIFE**：脚本体要能看见 GM_* 这些局部变量，
 * 由 [GmBridge.wrap] 在末尾补 `})();`。
 */
private const val PRELUDE = """
(function(){
var __id = %SCRIPT_ID%;
var __meta = %SCRIPT_META%;
var __bridge = window.__olfactGmBridge || null;
window.__olfactGmCallbacks = window.__olfactGmCallbacks || {};
window.__olfactGmSeq = window.__olfactGmSeq || 0;
if (!window.__olfactGmResolve) {
  window.__olfactGmResolve = function(callId, payloadJson) {
    var cb = window.__olfactGmCallbacks[callId];
    if (!cb) return;
    delete window.__olfactGmCallbacks[callId];
    var p = {};
    try { p = JSON.parse(payloadJson); } catch (e) { p = {}; }
    try {
      if (p.error) { if (cb.onerror) cb.onerror(p); }
      else { if (cb.onload) cb.onload(p); }
    } catch (e) {}
  };
}
function __call(method, args) {
  if (!__bridge || !__bridge.dispatch) return null;
  try { return String(__bridge.dispatch(__id, "", method, JSON.stringify(args || []))); }
  catch (e) { return null; }
}
function __async(method, args, cb) {
  if (!__bridge || !__bridge.dispatch) { if (cb && cb.onerror) cb.onerror({ error: "GM bridge unavailable" }); return; }
  var callId = __id + ":" + (++window.__olfactGmSeq);
  window.__olfactGmCallbacks[callId] = cb || {};
  try { __bridge.dispatch(__id, callId, method, JSON.stringify(args || [])); }
  catch (e) { delete window.__olfactGmCallbacks[callId]; if (cb && cb.onerror) cb.onerror({ error: String(e) }); }
}
var GM_info = { script: __meta, scriptHandler: "olfact", version: "0.1" };
var GM_getValue = function(key, def) {
  var raw = __call("get", [String(key)]);
  if (raw === null || raw === undefined || raw === "null") return def;
  try { return JSON.parse(raw); } catch (e) { return def; }
};
var GM_setValue = function(key, value) { __call("set", [String(key), JSON.stringify(value === undefined ? null : value)]); };
var GM_deleteValue = function(key) { __call("delete", [String(key)]); };
var GM_listValues = function() { var raw = __call("keys", []); try { return JSON.parse(raw) || []; } catch (e) { return []; } };
var GM_log = function() {
  var parts = [];
  for (var i = 0; i < arguments.length; i++) parts.push(String(arguments[i]));
  __call("log", [parts.join(" ")]);
  if (window.console && console.log) { try { console.log.apply(console, arguments); } catch (e) {} }
};
var GM_addStyle = function(css) {
  var s = document.createElement("style");
  s.textContent = String(css);
  (document.head || document.documentElement).appendChild(s);
  return s;
};
var GM_xmlhttpRequest = function(details) { __async("xhr", [details], details); };
var GM_openInTab = function(url) { window.open(String(url), "_blank"); };
var GM_setClipboard = function(text) { try { if (navigator.clipboard) navigator.clipboard.writeText(String(text)); } catch (e) {} };
var GM_registerMenuCommand = function() {};
var GM_notification = function(details) { try { if (window.Notification && details && details.text) new Notification(String(details.title || "脚本"), { body: String(details.text) }); } catch (e) {} };
var unsafeWindow = window;
window.GM_info = GM_info;
window.GM_getValue = GM_getValue;
window.GM_setValue = GM_setValue;
window.GM_deleteValue = GM_deleteValue;
window.GM_listValues = GM_listValues;
window.GM_log = GM_log;
window.GM_addStyle = GM_addStyle;
window.GM_xmlhttpRequest = GM_xmlhttpRequest;
window.unsafeWindow = window;
"""
