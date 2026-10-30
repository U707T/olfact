package cn.olfact.app.data

import android.content.Context
import cn.olfact.core.api.AiProvider
import cn.olfact.core.api.DownloadCenter
import cn.olfact.core.api.DownloadState
import cn.olfact.core.api.DownloadTask
import cn.olfact.core.api.OlfactServices
import cn.olfact.core.api.ProviderStore
import cn.olfact.core.api.ScriptStore
import cn.olfact.core.api.UserScript
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.json.JSONObject
import java.util.UUID

/**
 * 各模块尚未接入时的本地兜底实现。
 *
 * 原则：**只在接口为 null 时注册**，绝不覆盖真正的模块实现；
 * 下载在 debug 包里塞 3 条示例任务，release 不塞（见 [seedDemo]）。
 */
object LocalBackends {

    var usingDemoDownloads = false
        private set

    fun install(context: Context) {
        if (OlfactServices.downloads == null) {
            val store = LocalDownloadCenter(context)
            OlfactServices.downloads = store
            usingDemoDownloads = store.seedDemoIfNeeded(cn.olfact.app.BuildConfig.DEBUG)
        }
        if (OlfactServices.scriptStore == null) {
            OlfactServices.scriptStore = LocalScriptStore(context)
        }
        if (OlfactServices.aiProviders == null) {
            OlfactServices.aiProviders = LocalProviderStore(context)
        }
    }
}

/** 本地下载中心：为 UI 联调保留，真正下载由 B3 的 :download 模块接管。 */
class LocalDownloadCenter(private val context: Context) : DownloadCenter {

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    override val changes: Flow<Unit> = _changes
    private val items = mutableListOf<DownloadTask>()

    fun seedDemoIfNeeded(debugBuild: Boolean): Boolean {
        if (!debugBuild || items.isNotEmpty()) return false
        val now = System.currentTimeMillis()
        items += DownloadTask(
            id = UUID.randomUUID().toString(), name = "vidma_20260723_014032.mp4",
            url = "https://example.com/media/vidma_20260723_014032.mp4",
            state = DownloadState.DONE, totalBytes = 48_372_941, doneBytes = 48_372_941,
            createdAt = now - 7_200_000, finishedAt = now - 6_800_000, savePath = "/Download/vidma_20260723_014032.mp4"
        )
        items += DownloadTask(
            id = UUID.randomUUID().toString(), name = "访谈_第12期_1080p.mp4",
            url = "https://example.com/media/interview-12.mp4",
            state = DownloadState.DONE, totalBytes = 812_394_112, doneBytes = 812_394_112,
            createdAt = now - 86_400_000, finishedAt = now - 86_000_000, savePath = "/Download/访谈_第12期_1080p.mp4"
        )
        items += DownloadTask(
            id = UUID.randomUUID().toString(), name = "demo_playlist.m3u8",
            url = "https://example.com/media/demo/master.m3u8",
            state = DownloadState.RUNNING, totalBytes = 26_843_545, doneBytes = 11_274_289,
            speedBytesPerSec = 1_887_436, createdAt = now - 60_000, savePath = "/Download/demo_playlist"
        )
        _changes.tryEmit(Unit)
        return true
    }

    override fun tasks(): List<DownloadTask> = items.toList()

    override fun enqueue(url: String, name: String?, pageUrl: String) {
        items.add(0, DownloadTask(UUID.randomUUID().toString(), name ?: url.substringAfterLast('/'), url, DownloadState.QUEUED, pageUrl = pageUrl))
        _changes.tryEmit(Unit)
    }

    override fun pause(id: String) { update(id) { it.copy(state = DownloadState.PAUSED) } }
    override fun resume(id: String) { update(id) { it.copy(state = DownloadState.RUNNING) } }

    override fun remove(id: String, deleteFile: Boolean) {
        items.removeAll { it.id == id }
        _changes.tryEmit(Unit)
    }

    override fun clearFinished() {
        items.removeAll { it.state == DownloadState.DONE }
        _changes.tryEmit(Unit)
    }

    private fun update(id: String, block: (DownloadTask) -> DownloadTask) {
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) {
            items[i] = block(items[i])
            _changes.tryEmit(Unit)
        }
    }
}

/** 本地脚本库：内置一条示例脚本，证明注入链路与编辑器可用。 */
class LocalScriptStore(context: Context) : ScriptStore {

    private val prefs = context.getSharedPreferences("olfact_scripts", Context.MODE_PRIVATE)

    override fun all(): List<UserScript> {
        val raw = prefs.getString("list", null) ?: return listOf(SAMPLE)
        return runCatching {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).map { i -> parse(arr.getJSONObject(i)) }
        }.getOrDefault(listOf(SAMPLE))
    }

    override fun save(script: UserScript) {
        val list = all().toMutableList()
        list.removeAll { it.id == script.id }
        list.add(0, script)
        persist(list)
    }

    override fun remove(id: String) {
        persist(all().filterNot { it.id == id })
    }

    override fun setEnabled(id: String, enabled: Boolean) {
        persist(all().map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    private fun persist(list: List<UserScript>) {
        val arr = org.json.JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id); put("name", s.name); put("version", s.version)
                put("matches", org.json.JSONArray(s.matches)); put("enabled", s.enabled); put("code", s.code)
            })
        }
        prefs.edit().putString("list", arr.toString()).apply()
    }

    private fun parse(o: JSONObject): UserScript {
        val m = o.optJSONArray("matches") ?: org.json.JSONArray()
        return UserScript(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name"),
            version = o.optString("version", "1.0"),
            matches = (0 until m.length()).map { m.optString(it) },
            enabled = o.optBoolean("enabled", true),
            code = o.optString("code")
        )
    }

    companion object {
        val SAMPLE = UserScript(
            id = "sample-clean-reader",
            name = "极简阅读（示例）",
            version = "1.0",
            matches = listOf("https://*/*"),
            enabled = false,
            code = "// 示例：去掉浮动广告与吸底栏\n(function () {\n  const css = document.createElement('style');\n  css.textContent = '.float-ad,.fixed-bottom{display:none!important}';\n  document.head.appendChild(css);\n})();"
        )
    }
}

/** 本地 AI 供应商库（key 只存本地，永不外传）。 */
class LocalProviderStore(context: Context) : ProviderStore {

    private val prefs = context.getSharedPreferences("olfact_ai", Context.MODE_PRIVATE)

    override fun all(): List<AiProvider> {
        val raw = prefs.getString("list", "[]") ?: "[]"
        return runCatching {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                AiProvider(o.optString("id"), o.optString("name"), o.optString("baseUrl"), o.optString("key"), o.optString("model"), o.optBoolean("enabled", true))
            }
        }.getOrDefault(emptyList())
    }

    override fun save(provider: AiProvider) {
        val list = all().filterNot { it.id == provider.id } + provider
        persist(list)
    }

    override fun remove(id: String) = persist(all().filterNot { it.id == id })

    override fun setEnabled(id: String, enabled: Boolean) =
        persist(all().map { if (it.id == id) it.copy(enabled = enabled) else it })

    override fun activeId(): String? = prefs.getString("active", null)

    override fun setActive(id: String?) {
        prefs.edit().apply { if (id == null) remove("active") else putString("active", id) }.apply()
    }

    private fun persist(list: List<AiProvider>) {
        val arr = org.json.JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id); put("name", p.name); put("baseUrl", p.baseUrl)
                put("key", p.apiKey); put("model", p.model); put("enabled", p.enabled)
            })
        }
        prefs.edit().putString("list", arr.toString()).apply()
    }
}
