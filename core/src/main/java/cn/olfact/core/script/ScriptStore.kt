package cn.olfact.core.script

import cn.olfact.core.b4.B4Log
import cn.olfact.core.b4.StoreFiles
import cn.olfact.core.network.requireAtMost
import cn.olfact.core.network.withCancellableResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
        if (alive.size != items.size) {
            runCatching { saveLocked(alive) }.onFailure { B4Log.w(TAG, "清理失效脚本索引失败", it) }
        }
        return alive
    }

    private fun saveLocked(items: List<UserScript>) {
        StoreFiles.writeAtomic(indexFile, json.encodeToString(IndexFile.serializer(), IndexFile(items = items)))
        _scripts.value = items
    }

    /**
     * 正文和索引分属两个文件，无法做单次原子提交；索引提交失败时恢复原正文，
     * 新脚本则删除刚写入的正文。旧正文无法读取时在改写前终止，避免丢失回滚点。
     */
    private fun saveBodyAndIndex(id: String, code: String, items: List<UserScript>) {
        val target = bodyFile(id)
        val previous = when {
            !target.exists() -> null
            !target.isFile -> throw IllegalStateException("脚本正文路径不是文件: $target")
            else -> target.readText()
        }
        StoreFiles.writeAtomic(target, code)
        try {
            saveLocked(items)
        } catch (failure: Throwable) {
            try {
                if (previous != null) {
                    StoreFiles.writeAtomic(target, previous)
                } else if (target.exists() && !target.delete()) {
                    throw IllegalStateException("无法删除未提交的脚本正文: $target")
                }
            } catch (rollbackFailure: Throwable) {
                failure.addSuppressed(rollbackFailure)
                B4Log.w(TAG, "脚本索引写入失败且正文回滚失败: $id", rollbackFailure)
            }
            throw failure
        }
    }

    private fun requireDistinctFile(id: String) {
        val key = fileId(id)
        require(_scripts.value.none { it.id != id && fileId(it.id) == key }) { "脚本文件名冲突" }
    }

    fun bodyFile(id: String): File = File(scriptsDir, "${fileId(id)}.user.js")

    /**
     * 文件名用的 id：只保留字母数字与 `-` `_`。
     *
     * id 可能来自备份文件（`restoreOne`）或界面传入，带 `/` 或 `..` 时
     * `File(scriptsDir, "$id.user.js")` 会写到脚本目录外面。
     * 与备份写入端的 sanitizeId 保持同一规则，保证读写一致。
     */
    private fun fileId(id: String): String =
        id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "script" }

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
        requireDistinctFile(meta.id)
        val items = _scripts.value.toMutableList()
        val idx = items.indexOfFirst { it.id == meta.id }
        if (idx >= 0) items[idx] = meta else items.add(meta)
        saveBodyAndIndex(meta.id, parsed.body.ifBlank { source }, items.sortedBy { it.name.lowercase() })
        B4Log.d(TAG, "安装脚本 ${meta.name} (${meta.id})，${parsed.body.length} 字符")
        return parsed.copy(meta = meta)
    }

    /** 从 URL 安装（GreasyFork 的 `*.user.js` 直链）。 */
    suspend fun installFromUrl(url: String, http: OkHttpClient): UserScript? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            http.newCall(request).withCancellableResponse { resp ->
                if (!resp.isSuccessful) {
                    B4Log.w(TAG, "下载脚本失败 HTTP ${resp.code}")
                    return@withCancellableResponse null
                }
                val text = resp.body.requireAtMost(MAX_BYTES.toLong()).string()
                val head = text.trimStart().take(64).lowercase()
                if (text.isBlank() || head.startsWith("<!doctype html") || head.startsWith("<html")) {
                    return@withCancellableResponse null
                }
                currentCoroutineContext().ensureActive()
                install(text, url)?.meta
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            B4Log.w(TAG, "下载脚本异常", e)
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
        // 编辑器里粘贴的完整 .user.js：正文带元数据头时，名称/匹配/调度以头为准，
        // 否则「未命名脚本 + *://*/*」会把脚本真正想匹配的页面冲掉。
        val header = UserScriptParser.parse(code)
        val item = meta.mergeHeader(header).copy(
            installedAt = existing?.installedAt ?: now,
            updatedAt = now
        )
        requireDistinctFile(item.id)
        val items = _scripts.value.toMutableList()
        val idx = items.indexOfFirst { it.id == item.id }
        if (idx >= 0) items[idx] = item else items.add(item)
        saveBodyAndIndex(item.id, code, items.sortedBy { it.name.lowercase() })
        return item
    }

    /** 头部写了哪个字段就采用哪个；启用状态、来源地址与安装时间始终以仓库里的为准。 */
    private fun UserScript.mergeHeader(header: UserScriptParser.Parsed?): UserScript {
        if (header == null) return this
        val declared = header.meta
        val keys = header.keys
        return copy(
            name = declared.name.takeIf { "name" in keys } ?: name,
            namespace = declared.namespace.takeIf { "namespace" in keys } ?: namespace,
            version = declared.version.takeIf { "version" in keys } ?: version,
            description = declared.description.takeIf { "description" in keys } ?: description,
            author = declared.author.takeIf { "author" in keys } ?: author,
            icon = declared.icon.takeIf { "icon" in keys } ?: icon,
            matches = declared.matches.takeIf { "match" in keys } ?: matches,
            includes = declared.includes.takeIf { "include" in keys } ?: includes,
            excludes = declared.excludes.takeIf { "exclude" in keys } ?: excludes,
            grants = declared.grants.takeIf { "grant" in keys } ?: grants,
            runAt = if ("run-at" in keys) declared.runAt else runAt
        )
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
        requireDistinctFile(item.id)
        val items = _scripts.value.toMutableList()
        val idx = items.indexOfFirst { it.id == item.id }
        if (idx >= 0) items[idx] = item else items.add(item)
        saveBodyAndIndex(item.id, code, items.sortedBy { it.name.lowercase() })
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
        saveLocked(items)
        if (items.none { fileId(it.id) == fileId(id) }) bodyFile(id).delete()
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

    /**
     * 命中该 URL 且处于 [runAt] 阶段、已启用的脚本。
     *
     * `@run-at document-body` 没有独立的注入时机（浏览内核只在 document-start、
     * document-end、document-idle 三处回调），按 document-end 处理；
     * 否则这类脚本在任何页面都不会执行，用户却看不到任何提示。
     */
    fun enabledFor(url: String, runAt: RunAt): List<UserScript> =
        _scripts.value.filter { script ->
            script.enabled && script.matches(url) && when (runAt) {
                RunAt.DOCUMENT_END -> script.runAt == RunAt.DOCUMENT_END || script.runAt == RunAt.BODY_END
                else -> script.runAt == runAt
            }
        }

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
