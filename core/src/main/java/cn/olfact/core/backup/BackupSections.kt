package cn.olfact.core.backup

import cn.olfact.core.adblock.Subscription
import cn.olfact.core.adblock.SubscriptionStore
import cn.olfact.core.adblock.WhitelistStore
import cn.olfact.core.script.ScriptStore
import cn.olfact.core.script.UserScriptParser

/**
 * 备份的"数据源"边界。
 *
 * 为什么要这层：书签/历史/设置分别归 B1（Room/DataStore）和 B5（app），B4 不能直接读别人的表。
 * 各自实现一个接口、在 Application 启动时注册即可；B4 只负责串联、打包、校验、合并。
 */
interface SettingsSection {
    fun exportSettings(): Map<String, String>
    fun importSettings(values: Map<String, String>, overwrite: Boolean): MergeReport
}

interface ListSection<T> {
    fun exportItems(): List<T>
    fun importItems(items: List<T>, options: MergeOptions): MergeReport
}

interface WhitelistSection {
    fun exportWhitelist(): List<String>
    fun importWhitelist(items: List<String>): MergeReport
}

class BackupProviders(
    val settings: SettingsSection? = null,
    val bookmarks: ListSection<BookmarkRecord>? = null,
    val history: ListSection<HistoryRecord>? = null,
    val scripts: ListSection<ScriptRecord>? = null,
    val subscriptions: ListSection<SubscriptionRecord>? = null,
    val whitelist: WhitelistSection? = null
)

// ---------------------------------------------------------------- B4 自带实现

/** 脚本：导出元数据 + 脚本体；导入按 id 合并，不删已有脚本。 */
class ScriptBackupSection(private val store: ScriptStore) : ListSection<ScriptRecord> {

    override fun exportItems(): List<ScriptRecord> =
        store.list().map { ScriptRecord(meta = it, body = store.body(it.id).orEmpty()) }

    override fun importItems(items: List<ScriptRecord>, options: MergeOptions): MergeReport {
        var added = 0
        var updated = 0
        var skipped = 0
        for (record in items) {
            val existing = store.find(record.meta.id)
            if (existing == null) {
                store.install(record.body, record.meta.sourceUrl)?.let {
                    store.setEnabled(record.meta.id, record.meta.enabled)
                }
                added++
            } else if (options.preferIncoming && record.meta.updatedAt > existing.updatedAt) {
                store.install(record.body, record.meta.sourceUrl)
                store.setEnabled(record.meta.id, record.meta.enabled)
                updated++
            } else {
                skipped++
            }
        }
        return MergeReport("scripts", added, updated, skipped)
    }
}

/** 订阅：只存配置，不存规则文本（规则可重新下载，塞进备份会让包体积爆掉）。 */
class SubscriptionBackupSection(private val store: SubscriptionStore) : ListSection<SubscriptionRecord> {

    override fun exportItems(): List<SubscriptionRecord> = store.items.value.map {
        SubscriptionRecord(
            id = it.id,
            title = it.title,
            url = it.url,
            enabled = it.enabled,
            builtinAsset = it.builtinAsset,
            lastUpdated = it.lastUpdated,
            ruleCount = it.ruleCount
        )
    }

    override fun importItems(items: List<SubscriptionRecord>, options: MergeOptions): MergeReport {
        var added = 0
        var updated = 0
        var skipped = 0
        val current = store.items.value.associateBy { it.id }
        for (record in items) {
            val existing = current[record.id]
            if (existing == null) {
                store.upsert(
                    Subscription(
                        id = record.id,
                        title = record.title,
                        url = record.url,
                        enabled = record.enabled,
                        builtinAsset = record.builtinAsset,
                        lastUpdated = record.lastUpdated,
                        ruleCount = record.ruleCount
                    )
                )
                added++
            } else if (options.preferIncoming && record.lastUpdated > existing.lastUpdated) {
                store.upsert(existing.copy(enabled = record.enabled, title = record.title, url = record.url))
                updated++
            } else {
                skipped++
            }
        }
        return MergeReport("subscriptions", added, updated, skipped)
    }
}

/** 白名单：纯字符串，去重后并入。 */
class WhitelistBackupSection(private val store: WhitelistStore) : WhitelistSection {

    override fun exportWhitelist(): List<String> = store.all()

    override fun importWhitelist(items: List<String>): MergeReport {
        var added = 0
        var skipped = 0
        for (domain in items) {
            if (store.add(domain)) added++ else skipped++
        }
        return MergeReport("whitelist", added, 0, skipped)
    }
}

/** 便捷工厂：B4 自己的三块一次装好。 */
fun defaultB4Providers(
    scripts: ScriptStore,
    subscriptions: SubscriptionStore,
    whitelist: WhitelistStore
): BackupProviders = BackupProviders(
    scripts = ScriptBackupSection(scripts),
    subscriptions = SubscriptionBackupSection(subscriptions),
    whitelist = WhitelistBackupSection(whitelist)
)

/** 供脚本导入时保持 id 稳定（备份里 body 为空时也能重建元数据）。 */
internal fun normalizeScriptId(meta: cn.olfact.core.script.UserScript): String =
    meta.id.ifBlank { UserScriptParser.hashId(meta.namespace, meta.name) }
