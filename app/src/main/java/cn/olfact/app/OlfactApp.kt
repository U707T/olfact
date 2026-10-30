package cn.olfact.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import cn.olfact.app.data.AppSettings
import cn.olfact.app.data.BookmarkStore
import cn.olfact.app.data.HistoryStore
import cn.olfact.app.data.K
import cn.olfact.app.data.LocalBackends
import cn.olfact.app.data.ModuleBackends

class OlfactApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)
        BookmarkStore.init(this)
        HistoryStore.init(this)
        // 先把 B2/B3/B4 的真实实现接到 core/api 契约上，
        // 再由 LocalBackends 给仍然为空的槽位装兜底（它只在 null 时注册）
        ModuleBackends.install(this)
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
