package cn.olfact.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import cn.olfact.app.data.AppSettings
import cn.olfact.app.data.BookmarkStore
import cn.olfact.app.data.HistoryStore
import cn.olfact.app.data.K
import cn.olfact.app.data.LocalBackends

class OlfactApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)
        BookmarkStore.init(this)
        HistoryStore.init(this)
        // 各模块未接入时安装本地兜底（不会覆盖真实实现）
        LocalBackends.install(this)
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
