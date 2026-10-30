package cn.olfact.core.storage.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 全应用唯一数据库。表只有三张，加表请走 B1 评审（schema 一旦发布就要写 Migration）。
 */
@Database(
    entities = [
        BookmarkEntity::class,
        HistoryEntity::class,
        DownloadEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class OlfactDatabase : RoomDatabase() {

    abstract fun bookmarks(): BookmarkDao

    abstract fun history(): HistoryDao

    abstract fun downloads(): DownloadDao

    companion object {
        const val DB_NAME = "olfact.db"

        fun create(context: Context): OlfactDatabase =
            Room.databaseBuilder(context.applicationContext, OlfactDatabase::class.java, DB_NAME)
                // 1.0 之前允许破坏性升级：表结构还在迭代，正式发版前改成 Migration
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
