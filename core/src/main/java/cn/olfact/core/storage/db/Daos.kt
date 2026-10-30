package cn.olfact.core.storage.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {

    @Query("SELECT * FROM bookmark ORDER BY sortOrder ASC, createdAt DESC")
    fun observeAll(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmark ORDER BY sortOrder ASC, createdAt DESC")
    suspend fun all(): List<BookmarkEntity>

    @Query("SELECT * FROM bookmark WHERE url = :url LIMIT 1")
    suspend fun findByUrl(url: String): BookmarkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bookmark: BookmarkEntity): Long

    @Update
    suspend fun update(bookmark: BookmarkEntity)

    @Delete
    suspend fun delete(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmark WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM bookmark")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM bookmark")
    suspend fun count(): Int
}

@Dao
interface HistoryDao {

    @Query("SELECT * FROM history ORDER BY visitedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history ORDER BY visitedAt DESC LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<HistoryEntity>

    @Query("SELECT * FROM history WHERE url = :url LIMIT 1")
    suspend fun findByUrl(url: String): HistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: HistoryEntity): Long

    @Query(
        "UPDATE history SET visitCount = visitCount + 1, visitedAt = :visitedAt, title = :title " +
            "WHERE url = :url"
    )
    suspend fun touch(url: String, title: String?, visitedAt: Long)

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM history WHERE visitedAt < :before")
    suspend fun deleteBefore(before: Long)

    @Query("DELETE FROM history")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM history")
    suspend fun count(): Int
}

@Dao
interface DownloadDao {

    @Query("SELECT * FROM download ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM download WHERE id = :id")
    suspend fun findById(id: Long): DownloadEntity?

    @Query("SELECT * FROM download WHERE status IN (0, 1, 2)")
    suspend fun unfinished(): List<DownloadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: DownloadEntity): Long

    @Query(
        "UPDATE download SET status = :status, downloadedBytes = :downloadedBytes, " +
            "totalBytes = :totalBytes, error = :error, updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun updateProgress(
        id: Long,
        status: Int,
        downloadedBytes: Long,
        totalBytes: Long,
        error: String?,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("DELETE FROM download WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM download WHERE status = 3")
    suspend fun clearFinished()

    @Query("SELECT COUNT(*) FROM download WHERE status IN (0, 1, 2)")
    suspend fun runningCount(): Int
}
