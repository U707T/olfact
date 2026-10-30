package cn.olfact.core.adblock

import cn.olfact.core.b4.B4Log
import cn.olfact.core.b4.StoreFiles
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 域名级白名单。优先级高于所有订阅（见任务书"白名单：域名级，优先于订阅"）。
 * 支持粘贴整条 URL / `*.example.com`，落盘前统一规范化成纯域名。
 */
class WhitelistStore(
    private val file: File,
    private val json: Json = Json { prettyPrint = true }
) {

    private val domains = LinkedHashSet<String>()

    init {
        load()
    }

    @Synchronized
    fun load() {
        val text = StoreFiles.readText(file)
        if (text.isNullOrBlank()) return
        runCatching {
            json.decodeFromString(ListSerializer(String.serializer()), text)
        }.onSuccess { domains.clear(); domains.addAll(it.mapNotNull(::normalize)) }
            .onFailure { B4Log.w("Whitelist", "whitelist.json 损坏，忽略", it) }
    }

    @Synchronized
    fun add(raw: String): Boolean {
        val d = normalize(raw) ?: return false
        if (!domains.add(d)) return false
        save()
        return true
    }

    @Synchronized
    fun remove(raw: String): Boolean {
        val d = normalize(raw) ?: return false
        if (!domains.remove(d)) return false
        save()
        return true
    }

    /** host 本身就是白名单域名，或是它的子域。 */
    @Synchronized
    fun contains(host: String): Boolean {
        if (host.isEmpty()) return false
        val h = host.lowercase().trimEnd('.')
        if (domains.isEmpty()) return false
        if (domains.contains(h)) return true
        return domains.any { h.endsWith(".$it") }
    }

    @Synchronized
    fun all(): List<String> = domains.toList()

    @Synchronized
    fun clear() {
        if (domains.isEmpty()) return
        domains.clear()
        save()
    }

    private fun save() {
        runCatching {
            StoreFiles.writeAtomic(file, json.encodeToString(ListSerializer(String.serializer()), domains.toList()))
        }.onFailure { B4Log.e("Whitelist", "写入白名单失败", it) }
    }

    companion object {
        fun normalize(raw: String): String? {
            var s = raw.trim().lowercase()
            if (s.isEmpty()) return null
            s = s.replace(" ", "")
            if (s.contains("://")) s = UrlTools.hostOf(s)
            s = s.removePrefix("*.").removePrefix(".").trimEnd('.')
            if (s.isEmpty() || s.length > 253) return null
            if (s.any { it == '/' || it == '?' || it == '#' || it == '@' }) return null
            if (!s.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' || it == ':' || it == '[' || it == ']' }) return null
            return s.ifEmpty { null }
        }
    }
}
