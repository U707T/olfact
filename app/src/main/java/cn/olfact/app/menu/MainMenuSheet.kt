package cn.olfact.app.menu

import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import cn.olfact.app.MainActivity
import cn.olfact.app.R
import cn.olfact.app.data.AppSettings
import cn.olfact.app.data.BookmarkStore
import cn.olfact.app.data.BrowserSession
import cn.olfact.app.data.K
import cn.olfact.app.nav.Nav
import cn.olfact.app.ui.bookmark.BookmarksFragment
import cn.olfact.app.ui.browser.BrowserFragment
import cn.olfact.app.ui.download.DownloadListFragment
import cn.olfact.app.ui.history.HistoryFragment
import cn.olfact.app.ui.player.SnifferSheet
import cn.olfact.app.ui.settings.pages.ScriptFragment
import cn.olfact.app.ui.settings.SettingsFragment
import cn.olfact.app.ui.settings.SettingsPageFragment
import cn.olfact.app.ui.settings.pages.LogFragment
import cn.olfact.core.uikit.NineGrid
import cn.olfact.core.uikit.Sheet
import cn.olfact.core.uikit.dpF

/**
 * 主菜单：5×3 九宫格 + 底部三个动作（原版 docs/refs/09-主菜单九宫格.jpg）。
 */
object MainMenuSheet {

    private data class Item(val icon: Int, val label: String, val action: (MainActivity) -> Unit)

    fun show(activity: MainActivity) {
        val sheet = Sheet(activity)
        val grid = NineGrid(activity)
        val items = listOf(
            Item(R.drawable.ic_bookmark_add, "加书签") { addBookmark(it) },
            Item(R.drawable.ic_bookmark, "书签") { Nav.push(BookmarksFragment()) },
            Item(R.drawable.ic_history, "历史") { Nav.push(HistoryFragment()) },
            Item(R.drawable.ic_download, "下载") { Nav.push(DownloadListFragment()) },
            Item(R.drawable.ic_code, "脚本") { Nav.push(ScriptFragment()) },
            Item(R.drawable.ic_play, "嗅探") { SnifferSheet.show(it) },
            Item(R.drawable.ic_phone, "UA标识") { Nav.push(SettingsPageFragment.newInstance("ua")) },
            Item(R.drawable.ic_list, "网络日志") { Nav.push(LogFragment()) },
            Item(R.drawable.ic_refresh, "刷新") { reload() },
            Item(R.drawable.ic_incognito, "无痕模式") { openIncognito(it) },
            Item(R.drawable.ic_star, "收藏") { Nav.push(cn.olfact.app.ui.player.FavoritesFragment()) },
            Item(R.drawable.ic_qr, "二维码") { Nav.push(cn.olfact.app.ui.tools.QrShareFragment.newInstance("")) },
            Item(R.drawable.ic_moon, "夜间模式") { toggleNight(it) },
            Item(R.drawable.ic_globe, "网站配置") { Nav.push(SettingsPageFragment.newInstance("site")) },
            Item(R.drawable.ic_settings, "设置") { Nav.push(SettingsFragment()) }
        )
        grid.setItems(items.map { NineGrid.Item(it.icon, it.label) }) { index ->
            sheet.dismiss()
            items[index].action(activity)
        }
        sheet.add(grid)

        val footer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dpF(28f), activity.dpF(6f), activity.dpF(28f), activity.dpF(20f))
        }
        listOf(
            R.drawable.ic_moon to { toggleNight(activity) },
            R.drawable.ic_arrow_right to { sheet.dismiss() },
            R.drawable.ic_settings to { sheet.dismiss(); Nav.push(SettingsFragment()) }
        ).forEach { (icon, action) ->
            footer.addView(ImageView(activity).apply {
                setImageResource(icon)
                imageTintList = ContextCompat.getColorStateList(activity, R.color.text_primary)
                setPadding(activity.dpF(10f), activity.dpF(10f), activity.dpF(10f), activity.dpF(10f))
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, activity.dpF(44f), 1f))
        }
        sheet.add(footer)
        sheet.show()
    }

    private fun addBookmark(activity: MainActivity) {
        val tab = BrowserSession.current()
        val url = tab?.url?.ifBlank { null } ?: return Nav.toast("当前没有可收藏的页面")
        BookmarkStore.add(activity, tab.title.ifBlank { url }, url)
        Nav.toast("已加入书签")
    }

    private fun reload() {
        (Nav.current() as? BrowserFragment)?.reload()
    }

    private fun openIncognito(activity: MainActivity) {
        val tab = BrowserSession.open(null, "无痕标签页", incognito = true)
        Nav.push(BrowserFragment.newInstance(tab.id))
    }

    private fun toggleNight(activity: MainActivity) {
        val next = !AppSettings.get(K.NIGHT_MODE).let { it == 1 }
        AppSettings.set(K.NIGHT_MODE, if (next) 1 else 2)
        AppCompatDelegate.setDefaultNightMode(
            if (next) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }
}
