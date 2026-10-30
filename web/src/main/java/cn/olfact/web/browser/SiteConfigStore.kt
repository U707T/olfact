package cn.olfact.web.browser

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

private val Context.siteConfigDataStore: DataStore<Preferences> by preferencesDataStore(name = "olfact_sites")

/**
 * 按域名的站点配置（JS / 图片 / 夜间 / 桌面 UA / DoH）。
 *
 * 用 DataStore 存一份 JSON 映射：站点条目通常是几十条，整表读写的成本远低于引入 Room。
 * 读路径走内存缓存，[configFor] 可以在 UI 线程直接调用。
 */
class SiteConfigStore(private val context: Context) {

    private val key = stringPreferencesKey(KEY_SITES)
    private val cache = ConcurrentHashMap<String, SiteConfig>()

    @Volatile
    private var loaded = false

    suspend fun load() {
        val raw = runCatching { context.siteConfigDataStore.data.first()[key] }.getOrNull()
        cache.clear()
        cache.putAll(decode(raw))
        loaded = true
    }

    fun isLoaded(): Boolean = loaded

    fun configFor(host: String): SiteConfig {
        val normalized = SiteConfig.normalizeHost(host)
        return cache[normalized] ?: SiteConfig(host = normalized)
    }

    suspend fun update(config: SiteConfig) {
        val normalized = SiteConfig.normalizeHost(config.host)
        if (normalized.isEmpty()) return
        if (!loaded) load()
        val merged = HashMap(cache)
        if (config.isDefault()) merged.remove(normalized) else merged[normalized] = config.copy(host = normalized)
        persist(merged)
    }

    suspend fun remove(host: String) {
        val normalized = SiteConfig.normalizeHost(host)
        if (!loaded) load()
        val merged = HashMap(cache)
        merged.remove(normalized)
        persist(merged)
    }

    val all: Flow<List<SiteConfig>>
        get() = context.siteConfigDataStore.data.map { prefs -> decode(prefs[key]).values.sortedBy { it.host } }

    private suspend fun persist(sites: Map<String, SiteConfig>) {
        val json = JSONArray()
        sites.values.forEach { site ->
            json.put(
                JSONObject().apply {
                    put("host", site.host)
                    site.javaScriptEnabled?.let { put("js", it) }
                    site.imagesEnabled?.let { put("images", it) }
                    site.nightMode?.let { put("night", it) }
                    site.desktopMode?.let { put("desktop", it) }
                    site.customUa?.let { put("ua", it) }
                    site.dohEnabled?.let { put("doh", it) }
                }
            )
        }
        context.siteConfigDataStore.edit { prefs -> prefs[key] = json.toString() }
        cache.clear()
        cache.putAll(sites)
        loaded = true
    }

    private fun decode(raw: String?): Map<String, SiteConfig> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val array = JSONArray(raw)
            buildMap {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val host = item.optString("host").takeIf { it.isNotBlank() } ?: continue
                    put(
                        host,
                        SiteConfig(
                            host = host,
                            javaScriptEnabled = item.optBooleanOrNull("js"),
                            imagesEnabled = item.optBooleanOrNull("images"),
                            nightMode = item.optBooleanOrNull("night"),
                            desktopMode = item.optBooleanOrNull("desktop"),
                            customUa = item.optString("ua").takeIf { it.isNotBlank() },
                            dohEnabled = item.optBooleanOrNull("doh")
                        )
                    )
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun JSONObject.optBooleanOrNull(name: String): Boolean? =
        if (has(name) && !isNull(name)) optBoolean(name) else null

    private fun SiteConfig.isDefault(): Boolean =
        javaScriptEnabled == null && imagesEnabled == null && nightMode == null &&
            desktopMode == null && customUa == null && dohEnabled == null

    companion object {
        private const val KEY_SITES = "sites_json"
    }
}
