package cn.olfact.core.script

import cn.olfact.core.b4.B4Log
import cn.olfact.core.b4.StoreFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * 脚本仓库：`index.json`（元数据）+ `scripts/<id>.user.js`（脚本体）。
 *
 * 与 ROOM 的关系：任务书写"本地文件 + Room 索引"，但 Room 单库归 B1；
 * 为了让 B4 不碰 `core/storage`、也不阻塞 B1，这里用同样的原子 JSON 索引实现，
 * 接口（list/enable/delete/body）保持不变，后续要换 Room 只替换本类内部。
 */
class ScriptStore(
    private val dir: File,
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
) {

    @Serializable
    private data class IndexFile(val version: Int = 1, val items: List<UserScript> = emptyList())

    private val indexFile = File(dir, "index.json")
    private val scriptsDir = File(dir, "scripts")

    private val _scripts = MutableStateFlow<List<UserScript>>(emptyList())
    val scripts: StateFlow<List<UserScript>> = _scripts.asStateFlow()

    init {
        StoreFiles.ensureDir(dir)
        StoreFiles.ensureDir(scriptsDir)
        load()
    }

    @Synchronized
    fun load(): List<UserScript> {
        val text = StoreFiles.readText(indexFile)
        val items = if (text.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching { json.decodeFromString(IndexFile.serializer(), text) }
                .onFailure { B4Log.w(TAG, "index.json 损坏，重建索引", it) }
                .getOrNull()
                ?.items
                ?: emptyList()
        }
        // 文件被外部删掉时，索引里的条目也要清掉，避免"装了但注入空"
        val alive = items.filter { bodyFile(it.id).isFile }
        _scripts.value = alive
        if (alive.size != items.size) saveLocked(alive)
        return alive
    }

    @Synchronized
    private fun saveLocked(items: List<UserScript>) {
        _scripts.value = items
        runCatching {
            StoreFiles.writeAtomic(indexFile, json.encodeToString(IndexFile.serializer(), IndexFile(items = items)))
        }.onFailure { B4Log.e(TAG, "写入 index.json 失败", it) }
    }

    fun bodyFile(id: String): File = File(scriptsDir, "$id.user.js")

    fun body(id: String): String? = StoreFiles.readText(bodyFile(id))

    fun list(): List<UserScript> = _scripts.value

    fun find(id: String): UserScript? = _scripts.value.firstOrNull { it.id == id }

    /**
     * 安装或更新。返回 null 表示解析失败（调用方给用户提示）。
     */
    @Synchronized
    fun install(source: String, sourceUrl: String? = null): UserScriptParser.Parsed? {
        val parsed = UserScriptParser.parse(source, sourceUrl) ?: return null
        val existing = find(parsed.meta.id)
        val meta = parsed.meta.copy(
            enabled = existing?.enabled ?: true,
            installedAt = existing?.installedAt ?: parsed.meta.installedAt,
            updatedAt = System.currentTimeMillis()
        )
        StoreFiles.writeAtomic(bodyFile(meta.id), parsed.body.ifBlank { source })
        val items = _scripts.value.toMutableList()
        val idx = items.indexOfFirst { it.id == meta.id }
        if (idx >= 0) items[idx] = meta else items.add(meta)
        saveLocked(items.sortedBy { it.name.lowercase() })
        B4Log.d(TAG, "安装脚本 ${meta.name} (${meta.id})，${parsed.body.length} 字符")
        return parsed.copy(meta = meta)
    }

    /** 从 URL 安装（GreasyFork 的 `*.user.js` 直链）。 */
    suspend fun installFromUrl(url: String, http: OkHttpClient): UserScript? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    B4Log.w(TAG, "下载脚本失败 HTTP ${resp.code}: $url")
                    return@withContext null
                }
                val text = resp.body.string()
                if (text.isBlank() || text.length > MAX_BYTES) return@withContext null
                install(text, url)?.meta
            }
        } catch (t: Throwable) {
            B4Log.w(TAG, "下载脚本异常：$url", t)
            null
        }
    }

    /**
     * 供界面层直接保存「元数据 + 脚本体」（脚本编辑器用）。
     *
     * 与 [install] 的区别：install 走 `==UserScript==` 头解析，适合导入 .user.js；
     * 这里接受已结构化的元数据，不做解析，写盘后立即可注入。
     */
    @Synchronized
    fun save(meta: UserScript, code: String): UserScript {
        val now = System.currentTimeMillis()
        val existing = find(meta.id)
        val item = meta.copy(
            installedAt = existing?.installedAt ?: now,
            updatedAt = now
        )
        StoreFiles.writeAtomic(bodyFile(item.id), code)
        val items = _scripts.value.toMutableList()
        val idx = items.indexOfFirst { it.id == item.id }
        if (idx >= 0) items[idx] = item else items.add(item)
        saveLocked(items.sortedBy { it.name.lowercase() })
        return item
    }

    /**
     * 备份恢复专用：把「元数据 + 脚本体」原样写回。
     *
     * 与 [save] 的区别：保留备份里的 installedAt / updatedAt / enabled，
     * 不把「更新时间」改成 now——否则恢复后所有脚本都变成「刚刚更新」，
     * 之后再按时间合并时永远分不出新旧。
     */
    @Synchronized
    fun restoreOne(meta: UserScript, code: String): UserScript {
        val item = meta.copy(
            installedAt = meta.installedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
        )
        StoreFiles.writeAtomic(bodyFile(item.id), code)
        val items = _scripts.value.toMutableList()
        val idx = items.indexOfFirst { it.id == item.id }
        if (idx >= 0) items[idx] = item else items.add(item)
        saveLocked(items.sortedBy { it.name.lowercase() })
        return item
    }

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean): Boolean {
        val items = _scripts.value.toMutableList()
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) return false
        items[idx] = items[idx].copy(enabled = enabled, updatedAt = System.currentTimeMillis())
        saveLocked(items)
        return true
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val items = _scripts.value.toMutableList()
        val removed = items.removeAll { it.id == id }
        if (!removed) return false
        bodyFile(id).delete()
        saveLocked(items)
        return true
    }

    @Synchronized
    fun rename(id: String, newName: String): Boolean {
        val items = _scripts.value.toMutableList()
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) return false
        items[idx] = items[idx].copy(name = newName, updatedAt = System.currentTimeMillis())
        saveLocked(items)
        return true
    }

    /** 命中该 URL 且处于 [runAt] 阶段、已启用的脚本。 */
    fun enabledFor(url: String, runAt: RunAt): List<UserScript> =
        _scripts.value.filter { it.enabled && it.runAt == runAt && it.matches(url) }

    fun count(): Int = _scripts.value.size

    /** 备份/恢复用：直接替换整张索引（调用方负责 body 文件）。 */
    @Synchronized
    fun restore(items: List<UserScript>) {
        saveLocked(items)
    }

    companion object {
        private const val TAG = "ScriptStore"
        private const val MAX_BYTES = 2 * 1024 * 1024
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) olfact/0.1 (userscript)"
    }
}
