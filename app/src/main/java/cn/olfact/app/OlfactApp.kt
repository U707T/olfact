package cn.olfact.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import cn.olfact.app.data.AppSettings
import cn.olfact.app.data.BookmarkStore
import cn.olfact.app.data.FavoriteStore
import cn.olfact.app.data.HistoryStore
import cn.olfact.app.data.K
import cn.olfact.app.data.ModuleBackends

class OlfactApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installLogSink()
        AppSettings.init(this)
        // 下载设置与设置页共用 DataStore；不绑定的话队列会回退到模块私有 prefs（两套配置源）。
        cn.olfact.download.DownloadSettings.bind(AppSettings.repo)
        BookmarkStore.init(this)
        HistoryStore.init(this)
        FavoriteStore.init(this)
        // 真实模块接到 core/api 契约上：轻的同步、读盘的后台（见 ModuleBackends.install），
        // 备份流水线在后台链路末尾自行安装
        ModuleBackends.install(this)
        applyNightMode()
    }

    /**
     * 核心模块（拦截 / 脚本 / 备份 / 网络）只写 [cn.olfact.core.b4.B4Log]；不接管的话
     * 它只打到 stderr，「网络日志」页永远只有 app 层自己写的那几条。
     */
    private fun installLogSink() {
        val defaults = cn.olfact.core.b4.B4Log.sink
        cn.olfact.core.b4.B4Log.sink = { level, tag, message, error ->
            val text = if (error == null) message else "$message：${error.message ?: error.javaClass.simpleName}"
            when (level) {
                0 -> cn.olfact.app.data.AppLog.d(tag, text)
                1 -> cn.olfact.app.data.AppLog.w(tag, text)
                else -> cn.olfact.app.data.AppLog.e(tag, text)
            }
            runCatching { defaults(level, tag, message, error) }
        }
    }

    private fun applyNightMode() {
        val mode = when (AppSettings.get(K.NIGHT_MODE)) {
            1 -> AppCompatDelegate.MODE_NIGHT_YES
            2 -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
