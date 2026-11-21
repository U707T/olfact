package cn.olfact.core.adblock

import cn.olfact.core.b4.B4Log
import cn.olfact.core.net.AppDns
import cn.olfact.core.network.requireAtMost
import cn.olfact.core.network.withCancellableResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 订阅下载 / 内置清单落地。
 *
 * 关键约束（都是踩过的坑）：
 * - 带 `If-None-Match` / `If-Modified-Since`，304 视为成功，避免每次启动重下几 MB；
 * - 内容必须是文本，HTML 错误页（运营商劫持、校园网登录页）直接判失败，不能写进规则库；
 * - 单次下载上限 [MAX_BYTES]，防止被塞一个超大文件把内存打爆。
 */
class SubscriptionUpdater(
    private val store: SubscriptionStore,
    private val http: OkHttpClient,
    private val assetReader: ((String) -> String?)? = null,
    private val clock: () -> Long = System::currentTimeMillis
) {

    data class UpdateResult(
        val updated: Boolean = false,
        val notModified: Boolean = false,
        val ruleCount: Int = 0,
        val error: String? = null
    )

    data class UpdateSummary(
        val total: Int = 0,
        val updated: Int = 0,
        val notModified: Int = 0,
        val failed: Int = 0,
        val ruleCount: Int = 0
    )

    /** 把 assets 里的内置清单写到 `lists/`，只在缺失时写一次。 */
    suspend fun ensureBuiltinsInstalled() = withContext(Dispatchers.IO) {
        for (sub in store.items.value) {
            val asset = sub.builtinAsset ?: continue
            if (store.hasRules(sub.id)) continue
            val body = readAsset(asset)
            if (body.isNullOrBlank()) {
                B4Log.w("SubUpdater", "内置清单缺失：$asset")
                continue
            }
            store.writeRules(sub.id, body)
            store.update(sub.id) { it.copy(ruleCount = countRules(body), lastUpdated = clock()) }
            B4Log.d("SubUpdater", "内置清单 $asset 落地，${countRules(body)} 条")
        }
    }

    fun rulesText(sub: Subscription): String? {
        val cached = store.readRules(sub.id)
        if (!cached.isNullOrBlank()) return cached
        val asset = sub.builtinAsset ?: return null
        val body = readAsset(asset) ?: return null
        store.writeRules(sub.id, body)
        return body
    }

    suspend fun update(sub: Subscription, force: Boolean = false): UpdateResult = withContext(Dispatchers.IO) {
        if (sub.isBuiltin) return@withContext updateBuiltin(sub)
        if (sub.url.isBlank()) return@withContext UpdateResult(error = "订阅地址为空")
        if (!sub.enabled && !force) return@withContext UpdateResult(ruleCount = sub.ruleCount)

        try {
            val builder = Request.Builder()
                .url(sub.url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/plain,*/*")
            if (!force && store.hasRules(sub.id)) {
                sub.etag?.let { builder.header("If-None-Match", it) }
                sub.lastModified?.let { builder.header("If-Modified-Since", it) }
            }
            http.newCall(builder.build()).withCancellableResponse { resp ->
                when {
                    resp.code == 304 && store.hasRules(sub.id) -> {
                        store.update(sub.id) { it.copy(lastUpdated = clock(), lastError = null) }
                        UpdateResult(notModified = true, ruleCount = sub.ruleCount)
                    }

                    !resp.isSuccessful -> failure(sub, "HTTP ${resp.code}")

                    else -> {
                        val body = resp.body.requireAtMost(MAX_BYTES.toLong()).string()
                        val invalid = validate(body)
                        if (invalid != null) return@withCancellableResponse failure(sub, invalid)
                        currentCoroutineContext().ensureActive()
                        val count = countRules(body)
                        synchronized(store) {
                            val actual = store.items.value.firstOrNull { it.id == sub.id && it.url == sub.url }
                                ?: return@synchronized UpdateResult(error = "订阅已更改")
                            store.writeRules(actual.id, body)
                            store.upsert(actual.copy(
                                ruleCount = count,
                                lastUpdated = clock(),
                                etag = resp.header("ETag"),
                                lastModified = resp.header("Last-Modified"),
                                lastError = null
                            ))
                            B4Log.d("SubUpdater", "${sub.title} 更新成功，$count 条")
                            UpdateResult(updated = true, ruleCount = count)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            failure(sub, e.message ?: "网络错误")
        } catch (e: IllegalArgumentException) {
            failure(sub, "订阅地址或响应无效：${e.message ?: "未知原因"}")
        }
    }

    private fun failure(sub: Subscription, message: String): UpdateResult {
        runCatching {
            synchronized(store) {
                val current = store.items.value.firstOrNull { it.id == sub.id && it.url == sub.url }
                if (current != null) store.upsert(current.copy(lastError = message))
            }
        }.onFailure { B4Log.w("SubUpdater", "无法保存订阅错误", it) }
        B4Log.w("SubUpdater", "${sub.title} 更新失败：$message")
        return UpdateResult(error = message, ruleCount = sub.ruleCount)
    }

    suspend fun updateAll(force: Boolean = false): UpdateSummary = withContext(Dispatchers.IO) {
        var updated = 0
        var notModified = 0
        var failed = 0
        var rules = 0
        val enabled = store.items.value.filter { it.enabled }
        for (sub in enabled) {
            val r = update(sub, force)
            when {
                r.error != null -> failed++
                r.notModified -> notModified++
                r.updated -> updated++
            }
            rules += r.ruleCount
        }
        UpdateSummary(enabled.size, updated, notModified, failed, rules)
    }

    private fun updateBuiltin(sub: Subscription): UpdateResult {
        val asset = sub.builtinAsset ?: return UpdateResult(error = "内置清单未配置")
        val body = readAsset(asset) ?: return UpdateResult(error = "内置清单缺失：$asset")
        val count = countRules(body)
        if (store.readRules(sub.id) == body) {
            return UpdateResult(notModified = true, ruleCount = count)
        }
        store.writeRules(sub.id, body)
        store.update(sub.id) { it.copy(ruleCount = count, lastUpdated = clock(), lastError = null) }
        return UpdateResult(updated = true, ruleCount = count)
    }

    private fun readAsset(name: String): String? = try {
        assetReader?.invoke(name)?.takeIf { it.isNotBlank() }
    } catch (t: Throwable) {
        B4Log.w("SubUpdater", "读取 asset 失败：$name", t)
        null
    }

    private fun validate(body: String): String? {
        if (body.isBlank()) return "内容为空"
        if (body.length > MAX_BYTES) return "内容超过 ${MAX_BYTES / 1024 / 1024} MB"
        val head = body.take(512).lowercase()
        if (head.contains("<!doctype html") || head.contains("<html")) return "返回的是 HTML 页面"
        if (!body.contains("!") && !body.contains("##") && !body.contains("||")) return "不像规则文件"
        return null
    }

    companion object {
        private const val MAX_BYTES = 8 * 1024 * 1024
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) olfact/0.1 (adblock-filters)"

        fun countRules(text: String): Int = text.lineSequence().count { line ->
            val t = line.trim()
            t.isNotEmpty() && !t.startsWith("!") && !t.startsWith("[")
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .dns(AppDns)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
