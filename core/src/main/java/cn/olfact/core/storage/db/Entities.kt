package cn.olfact.core.storage.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 三个基础表的实体定义（B1 建骨架，B2/B3/B4/B5 只加 DAO 查询，不加表）。
 * 改表结构＝改 schema，必须在这里升 version 并写 Migration。
 */

@Entity(
    tableName = "bookmark",
    indices = [Index(value = ["url"], unique = true), Index("sortOrder")]
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val iconUrl: String? = null,
    /** 文件夹名，null 表示根目录 */
    val folder: String? = null,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "history",
    indices = [Index(value = ["url"], unique = true), Index("visitedAt"), Index("host")]
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String? = null,
    val url: String,
    val host: String? = null,
    val visitCount: Int = 1,
    val visitedAt: Long = System.currentTimeMillis(),
    /** 无痕记录不写库，这里留字段只为兼容历史数据 */
    val incognito: Boolean = false
)

@Entity(
    tableName = "download",
    indices = [Index("status"), Index("createdAt"), Index(value = ["url", "fileName"], unique = true)]
)
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    /** m3u8 存任务名，单文件存实际文件名 */
    val fileName: String,
    val filePath: String? = null,
    val mimeType: String? = null,
    /** [cn.olfact.core.api.MediaType] 的 name，存字符串避免枚举升级即迁移 */
    val mediaType: String? = null,
    val pageUrl: String? = null,
    /** -1 表示未知 */
    val totalBytes: Long = -1L,
    val downloadedBytes: Long = 0L,
    /** 见 STATUS_* 常量 */
    val status: Int = STATUS_PENDING,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_PENDING = 0
        const val STATUS_RUNNING = 1
        const val STATUS_PAUSED = 2
        const val STATUS_DONE = 3
        const val STATUS_FAILED = 4
        const val STATUS_CANCELED = 5
    }
}
