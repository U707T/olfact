package cn.olfact.core.adblock

import cn.olfact.core.b4.B4Log
import cn.olfact.core.b4.StoreFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 订阅清单的持久化：`subscriptions.json`（元数据）+ `lists/<id>.txt`（规则文本）。
 * 不依赖 Room——Room 单库归 B1，这里只碰自己的目录，避免跨批建表冲突。
 */
class SubscriptionStore(
    private val dir: File,
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
) {

    @Serializable
    private data class StoreFile(val version: Int = 1, val items: List<Subscription> = emptyList())

    private val indexFile = File(dir, "subscriptions.json")
    private val listsDir = File(dir, "lists")

    private val _items = MutableStateFlow<List<Subscription>>(emptyList())
    val items: StateFlow<List<Subscription>> = _items.asStateFlow()

    init {
        StoreFiles.ensureDir(dir)
        StoreFiles.ensureDir(listsDir)
        load()
    }

    @Synchronized
    fun load(): List<Subscription> {
        val text = StoreFiles.readText(indexFile)
        val parsed = if (text.isNullOrBlank()) {
            SubscriptionCatalog.builtins()
        } else {
            runCatching { json.decodeFromString(StoreFile.serializer(), text) }
                .onFailure { B4Log.w("SubStore", "subscriptions.json 损坏，重建", it) }
                .getOrNull()
                ?.items
                ?.takeIf { it.isNotEmpty() }
                ?: SubscriptionCatalog.builtins()
        }
        val sorted = parsed.sortedBy { it.order }
        _items.value = sorted
        if (text.isNullOrBlank() || parsed != sorted) saveLocked(sorted)
        return sorted
    }

    @Synchronized
    fun save(items: List<Subscription>) {
        val sorted = items.sortedBy { it.order }
        _items.value = sorted
        saveLocked(sorted)
    }

    private fun saveLocked(items: List<Subscription>) {
        runCatching {
            StoreFiles.writeAtomic(indexFile, json.encodeToString(StoreFile.serializer(), StoreFile(items = items)))
        }.onFailure { B4Log.e("SubStore", "写入 subscriptions.json 失败", it) }
    }

    @Synchronized
    fun upsert(sub: Subscription) {
        val current = _items.value.toMutableList()
        val idx = current.indexOfFirst { it.id == sub.id }
        if (idx >= 0) current[idx] = sub else current.add(sub)
        save(current)
    }

    @Synchronized
    fun update(id: String, transform: (Subscription) -> Subscription): Subscription? {
        val current = _items.value.toMutableList()
        val idx = current.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val updated = transform(current[idx])
        current[idx] = updated
        save(current)
        return updated
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val current = _items.value.toMutableList()
        val removed = current.removeAll { it.id == id }
        if (removed) {
            save(current)
            rulesFile(id).delete()
        }
        return removed
    }

    fun rulesFile(id: String): File = File(listsDir, "$id.txt")

    fun readRules(id: String): String? = StoreFiles.readText(rulesFile(id))

    fun writeRules(id: String, text: String) {
        StoreFiles.writeAtomic(rulesFile(id), text)
    }

    fun hasRules(id: String): Boolean = StoreFiles.sizeOf(rulesFile(id)) > 0

    fun totalRuleCount(): Int = _items.value.sumOf { it.ruleCount }
}
