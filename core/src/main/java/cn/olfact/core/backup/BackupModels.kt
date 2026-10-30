package cn.olfact.core.backup

import cn.olfact.core.script.UserScript
import kotlinx.serialization.Serializable

@Serializable
data class BookmarkRecord(
    val id: String,
    val title: String,
    val url: String,
    val folderId: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

@Serializable
data class HistoryRecord(
    val id: String,
    val title: String,
    val url: String,
    val visitedAt: Long = 0L,
    val visitCount: Int = 1
)

@Serializable
data class ScriptRecord(
    val meta: UserScript,
    val body: String = ""
)

@Serializable
data class SubscriptionRecord(
    val id: String,
    val title: String,
    val url: String = "",
    val enabled: Boolean = true,
    val builtinAsset: String? = null,
    val lastUpdated: Long = 0L,
    val ruleCount: Int = 0
)

/**
 * 备份正文。**只放数据，不放实现**：字段名就是格式契约，改动必须升 [BackupManifest.VERSION]。
 */
@Serializable
data class BackupData(
    val settings: Map<String, String> = emptyMap(),
    val bookmarks: List<BookmarkRecord> = emptyList(),
    val history: List<HistoryRecord> = emptyList(),
    val scripts: List<ScriptRecord> = emptyList(),
    val subscriptions: List<SubscriptionRecord> = emptyList(),
    val adblockWhitelist: List<String> = emptyList()
) {
    val isEmpty: Boolean
        get() = settings.isEmpty() && bookmarks.isEmpty() && history.isEmpty() &&
            scripts.isEmpty() && subscriptions.isEmpty() && adblockWhitelist.isEmpty()
}

@Serializable
data class BackupManifest(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val createdAt: Long = 0L,
    val appVersion: String = "",
    val sha256: String = "",
    val counts: Map<String, Int> = emptyMap()
) {
    companion object {
        const val FORMAT = "olfact-backup"
        const val VERSION = 1
        const val MIN_SUPPORTED = 1
    }
}

@Serializable
data class MergeOptions(
    /** 同一条记录冲突时，是否用备份里的（更新时间更新的才生效） */
    val preferIncoming: Boolean = true,
    /** 设置项是否允许覆盖已有值；默认 false = 只补缺 */
    val overwriteSettings: Boolean = false
)

@Serializable
data class MergeReport(
    val section: String = "",
    val added: Int = 0,
    val updated: Int = 0,
    val skipped: Int = 0
) {
    val changed: Int get() = added + updated

    operator fun plus(other: MergeReport): MergeReport = MergeReport(
        section = if (section.isEmpty()) other.section else section,
        added = added + other.added,
        updated = updated + other.updated,
        skipped = skipped + other.skipped
    )
}

@Serializable
data class ImportResult(
    val merged: List<MergeReport> = emptyList(),
    val error: String? = null
) {
    val changed: Int get() = merged.sumOf { it.changed }
}

@Serializable
data class BackupResult(
    val success: Boolean = false,
    val bytes: Long = 0L,
    val location: String = "",
    val trigger: String = "",
    val at: Long = 0L,
    val error: String? = null
)

@Serializable
data class BackupState(
    val lastRunAt: Long = 0L,
    val lastSuccess: Boolean = false,
    val lastBytes: Long = 0L,
    val lastLocation: String = "",
    val lastTrigger: String = "",
    val lastError: String? = null
)

class BackupFormatException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)
