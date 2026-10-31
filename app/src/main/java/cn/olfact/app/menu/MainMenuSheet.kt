package cn.olfact.app.menu

import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import cn.olfact.app.MainActivity
import cn.olfact.app.R
import cn.olfact.app.data.AppSettings
import cn.olfact.app.data.BookmarkStore
import cn.olfact.app.data.BrowserSession
import cn.olfact.app.data.K
import cn.olfact.app.nav.Nav
import cn.olfact.app.ui.ai.AiActions
import cn.olfact.app.ui.bookmark.BookmarksFragment
import cn.olfact.app.ui.browser.BrowserFragment
import cn.olfact.app.ui.download.DownloadListFragment
import cn.olfact.app.ui.history.HistoryFragment
import cn.olfact.app.ui.player.SnifferSheet
import cn.olfact.app.ui.settings.SettingsFragment
import cn.olfact.app.ui.settings.SettingsPageFragment
import cn.olfact.app.ui.settings.pages.LogFragment
import cn.olfact.app.ui.settings.pages.ScriptFragment
import cn.olfact.core.uikit.NineGrid
import cn.olfact.core.uikit.Sheet
import cn.olfact.core.uikit.dpF

/**
 * 主菜单：5 列 × 3 行 + 底部三个动作，图标全部用原版矢量资源（`orig_m_*`）。
 *
 * 与原版 `view_browser_menu_popup.xml` 的差异只在功能取舍：
 * 「阅读模式」按方案裁掉，换成 **AI 总结** 与 **翻译** 两个入口；其余标签保持原版文案。
 */
object MainMenuSheet {

    private data class Item(
        val icon: Int,
        val label: String,
        val tint: Boolean = true,
        val action: (MainActivity) -> Unit
    )

    fun show(activity: MainActivity) {
        val sheet = Sheet(activity)
        val grid = NineGrid(activity)
        val items = buildList {
            add(Item(R.drawable.orig_m_bookmark_add, "加书签") { addBookmark(it) })
            add(Item(R.drawable.orig_m_bookmark, "书签") { Nav.push(BookmarksFragment()) })
            add(Item(R.drawable.orig_m_history, "历史") { Nav.push(HistoryFragment()) })
            add(Item(R.drawable.orig_m_download, "下载") { Nav.push(DownloadListFragment()) })
            add(Item(R.drawable.orig_m_plugin, "插件") { Nav.push(ScriptFragment()) })
            add(Item(R.drawable.orig_m_sniff, "嗅探结果") { SnifferSheet.show(it) })
            add(Item(R.drawable.orig_m_ua, "UA标识") { Nav.push(SettingsPageFragment.newInstance("ua")) })
            if (AppSettings.get(K.DEV_MODE)) {
                add(Item(R.drawable.orig_m_log, "网络日志") { Nav.push(LogFragment()) })
            }
            add(Item(R.drawable.orig_m_refresh, "刷新") { reload() })
            add(Item(R.drawable.orig_m_box, "工具箱") { Nav.push(cn.olfact.app.ui.tools.ToolsFragment()) })
            add(Item(R.drawable.orig_m_incognito, "无痕模式") { openIncognito(it) })
            if (AppSettings.get(K.AI_SUMMARY)) {
                add(Item(R.drawable.orig_m_ai, "AI 总结", tint = false) { AiActions.summarize(it) })
            }
            add(Item(R.drawable.orig_s_fullscreen, "全屏") { toggleFullscreen(it) })
            add(Item(R.drawable.orig_m_night, "夜间模式") { toggleNight(it) })
            add(Item(R.drawable.orig_m_site, "网站配置") { Nav.push(SettingsPageFragment.newInstance("site")) })
        }
        grid.setItems(items.map { NineGrid.Item(it.icon, it.label, it.tint) }) { index ->
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
            R.drawable.orig_m_power to {
                activity.finishAffinity()
            },
            R.drawable.orig_m_collapse to {
                sheet.dismiss()
            },
            R.drawable.orig_m_settings to {
                sheet.dismiss()
                Nav.push(SettingsFragment())
            }
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
        val url = tab?.url?.ifBlank { null } ?: run { Nav.toast("当前没有可收藏的页面"); return }
        if (BookmarkStore.contains(url)) {
            Nav.toast("已经在书签里了")
            return
        }
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

    private fun toggleFullscreen(activity: MainActivity) = activity.toggleFullscreen()

    private fun toggleNight(activity: MainActivity) {
        val next = !AppSettings.get(K.NIGHT_MODE).let { it == 1 }
        AppSettings.set(K.NIGHT_MODE, if (next) 1 else 2)
        AppCompatDelegate.setDefaultNightMode(
            if (next) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }
}
