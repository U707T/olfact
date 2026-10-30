package cn.olfact.core

import android.content.Context
import android.content.pm.ApplicationInfo
import cn.olfact.core.api.SettingsRepository
import cn.olfact.core.log.Log
import cn.olfact.core.storage.DataStoreSettingsRepository
import cn.olfact.core.storage.db.OlfactDatabase

/**
 * core 的进程级入口。Application.onCreate 里调一次 [init]，之后各模块用
 * [Core.settings] / [Core.database] 拿单例，不要各自 new。
 */
object Core {

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        Log.enabled = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        Log.i("core init, debuggable=${Log.enabled}")
    }

    fun isInitialized(): Boolean = appContext != null

    val context: Context
        get() = appContext ?: error("Core.init(context) 未调用（应在 Application.onCreate 里）")

    val settings: SettingsRepository by lazy { DataStoreSettingsRepository(context) }

    val database: OlfactDatabase by lazy { OlfactDatabase.create(context) }
}
