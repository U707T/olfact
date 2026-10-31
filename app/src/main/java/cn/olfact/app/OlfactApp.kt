package cn.olfact.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import cn.olfact.app.data.AppSettings
import cn.olfact.app.data.BookmarkStore
import cn.olfact.app.data.HistoryStore
import cn.olfact.app.data.K
import cn.olfact.app.data.ModuleBackends

class OlfactApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)
        BookmarkStore.init(this)
        HistoryStore.init(this)
        // 真实模块接到 core/api 契约上：轻的同步、读盘的后台（见 ModuleBackends.install），
        // 备份流水线在后台链路末尾自行安装
        ModuleBackends.install(this)
        applyNightMode()
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
