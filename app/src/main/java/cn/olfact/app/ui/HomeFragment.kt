package cn.olfact.app.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import cn.olfact.app.R
import cn.olfact.app.data.BookmarkStore
import cn.olfact.app.data.HistoryStore
import cn.olfact.app.databinding.FragmentHomeBinding
import cn.olfact.app.nav.ChromeAware
import cn.olfact.app.nav.Nav
import cn.olfact.app.ui.bookmark.BookmarksFragment
import cn.olfact.app.ui.download.DownloadListFragment
import cn.olfact.app.ui.history.HistoryFragment
import cn.olfact.app.ui.settings.SettingsFragment
import cn.olfact.app.ui.settings.pages.ScriptFragment
import cn.olfact.core.api.OlfactServices
import cn.olfact.core.uikit.ListRow
import cn.olfact.core.uikit.dpF

/**
 * 首页：插画 + 搜索框 + 5 个快捷入口 + 语录卡 + 统计卡 + 最近访问。
 *
 * 结构对齐 docs/refs/07-首页.jpg：白/浅灰底、16dp 边距、卡片圆角 16dp。
 */
class HomeFragment : Fragment(R.layout.fragment_home), ChromeAware {

    override val showsBottomBar: Boolean = true
    override val barId: String = "home"

    private var binding: FragmentHomeBinding? = null

    private data class Shortcut(val label: String, val icon: Int, val action: () -> Unit)
    private data class Stat(val label: String, val count: () -> Int, val action: () -> Unit)

    private val quotes = listOf(
        "几时能命驾，对酒落花前。" to "贾岛",
        "山中何事？松花酿酒，春水煎茶。" to "张可久",
        "晚来天欲雪，能饮一杯无？" to "白居易",
        "醉后不知天在水，满船清梦压星河。" to "唐珙",
        "人间有味是清欢。" to "苏轼"
    )
    private var quoteIndex = 0
    private var quoteHintShown = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentHomeBinding.bind(view)
        binding = b

        b.urlBar.editable = false
        b.urlBar.setHint(getString(R.string.search_hint))
        b.urlBar.onBarClick = { Nav.openBrowser(null) }

        buildShortcuts(b.shortcuts)
        buildStats(b.statsCard)
        renderRecent(b.recentList)

        b.quoteText.text = quotes[quoteIndex].first
        b.quoteAuthor.text = quotes[quoteIndex].second
        b.quoteCard.setOnClickListener {
            quoteIndex = (quoteIndex + 1) % quotes.size
            b.quoteText.text = quotes[quoteIndex].first
            b.quoteAuthor.text = quotes[quoteIndex].second
            if (!quoteHintShown) {
                quoteHintShown = true
                Nav.toast("点按语录卡可换一句")
            }
        }

        b.recentMore.setOnClickListener { Nav.push(HistoryFragment()) }
    }

    override fun onResume() {
        super.onResume()
        binding?.let {
            buildStats(it.statsCard)
            renderRecent(it.recentList)
        }
    }

    private fun buildShortcuts(container: LinearLayout) {
        val items = listOf(
            Shortcut("书签", R.drawable.home_bookmark2) { Nav.push(BookmarksFragment()) },
            Shortcut("下载", R.drawable.home_download) { Nav.push(DownloadListFragment()) },
            Shortcut("历史", R.drawable.home_history2) { Nav.push(HistoryFragment()) },
            Shortcut("设置", R.drawable.home_setting) { Nav.push(SettingsFragment()) },
            Shortcut("小程序", R.drawable.home_miniapp) {
                Nav.toast("小程序已按方案裁掉，可用 PWA 代替")
            }
        )
        container.removeAllViews()
        val ctx = requireContext()
        items.forEach { item ->
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                setOnClickListener { item.action() }
                addView(ImageView(ctx).apply {
                    setImageResource(item.icon)
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                })
                addView(TextView(ctx).apply {
                    text = item.label
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(6) }
                })
            }
            container.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun buildStats(container: LinearLayout) {
        container.removeAllViews()
        val ctx = requireContext()
        val stats = listOf(
            Stat("收藏书签", { BookmarkStore.all().size }) { Nav.push(BookmarksFragment()) },
            Stat("媒体收藏", { cn.olfact.app.data.FavoriteStore.items.size }) { Nav.push(cn.olfact.app.ui.player.FavoritesFragment()) },
            Stat("文件下载", { OlfactServices.downloads?.tasks()?.size ?: 0 }) { Nav.push(DownloadListFragment()) },
            Stat("网页插件", { OlfactServices.scriptStore?.all()?.size ?: 0 }) { Nav.push(ScriptFragment()) }
        )
        stats.forEach { stat ->
            container.addView(ListRow(ctx).apply {
                title = "${stat.label}：${stat.count()}"
                onRowClick = stat.action
                showChevron(true)
                minimumHeight = dp(44)
                setPadding(dp(16), 0, dp(12), 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
        }
    }

    private fun renderRecent(container: LinearLayout) {
        container.removeAllViews()
        val recent = HistoryStore.recent(3)
        val ctx = requireContext()
        if (recent.isEmpty()) {
            container.addView(TextView(ctx).apply {
                text = "还没有浏览记录，去地址栏输入点什么吧"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                setPadding(dp(16), dp(14), dp(16), dp(14))
            })
            return
        }
        recent.forEachIndexed { index, entry ->
            if (index > 0) container.addView(divider(ctx))
            container.addView(ListRow(ctx).apply {
                title = entry.title
                subtitle = entry.url
                setIcon(R.drawable.ic_globe)
                onRowClick = { Nav.openBrowser(entry.url) }
            })
        }
    }

    private fun divider(ctx: android.content.Context) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
            marginStart = dp(52)
        }
        setBackgroundColor(ContextCompat.getColor(ctx, R.color.divider))
    }

    private fun dp(v: Int) = requireContext().dpF(v.toFloat())

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
