package cn.olfact.app.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import cn.olfact.app.link.SearchEngines
import cn.olfact.app.nav.ChromeAware
import cn.olfact.app.nav.Nav
import cn.olfact.app.ui.bookmark.BookmarksFragment
import cn.olfact.app.ui.browser.UrlSuggestPopup
import cn.olfact.app.ui.download.DownloadListFragment
import cn.olfact.app.ui.history.HistoryFragment
import cn.olfact.app.ui.settings.SettingsFragment
import cn.olfact.app.ui.settings.pages.ScriptFragment
import cn.olfact.app.ui.tools.ScanActivity
import cn.olfact.core.api.OlfactServices
import cn.olfact.core.uikit.ListRow
import cn.olfact.core.uikit.dpF

/**
 * 首页：插画 + 搜索框 + 5 个快捷入口 + 语录卡 + 统计卡 + 最近访问。
 *
 * 结构对齐 docs/refs/07-首页.jpg：白/浅灰底、16dp 边距、卡片圆角 16dp。
 * 统计卡是原版的三行列表（收藏书签 / 文件下载 / 网页插件），不是 2×2 数字格。
 */
class HomeFragment : Fragment(R.layout.fragment_home), ChromeAware {

    override val showsBottomBar: Boolean = true
    override val barId: String = "home"
    override val barTitle: String = "主页"

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
    private var statsSignature = ""
    private var recentSignature = ""

    private var suggest: UrlSuggestPopup? = null
    private val searchHandler = Handler(Looper.getMainLooper())
    private val searchDebounce = Runnable { openHomeSuggestions() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentHomeBinding.bind(view)
        binding = b

        // 首页搜索框现在是真输入框：点一下直接聚焦并弹键盘，提交后才进浏览器。
        // 旧实现「点一下先开一个空白浏览器，再点地址栏」多一步，也容易叠标签。
        b.urlBar.editable = true
        b.urlBar.setHint(getString(R.string.search_hint))
        // 原版首页搜索框是空白的，左侧没有 DNS 地球图标。
        b.urlBar.setLeadingIconVisible(false)
        b.urlBar.onSubmit = { text ->
            if (text.isNotBlank()) {
                suggest?.dismiss()
                b.urlBar.clearFocusCompat()
                Nav.openBrowser(SearchEngines.urlFor(text), reuseCurrent = true)
            }
        }
        b.urlBar.onActionClick = { openScanner() }
        b.urlBar.setActionIcon(R.drawable.orig_home_scan)
        b.urlBar.setActionDescription("扫一扫")
        b.urlBar.onTextChanged = { text ->
            searchHandler.removeCallbacks(searchDebounce)
            if (text.isNotBlank() && b.urlBar.editor.hasFocus()) {
                searchHandler.postDelayed(searchDebounce, 200)
            } else {
                suggest?.dismiss()
            }
        }

        buildShortcuts(b.shortcuts)
        // 关键：签名必须跟着 View 生命周期重置，并且**首次一定强制重建**。
        // HomeFragment 实例会被返回栈保留，但 View 会重建；如果签名还留着、
        // 或容器里刚好是空的，就会留下「统计卡空白、最近访问消失」的首页。
        statsSignature = ""
        recentSignature = ""
        refreshHomeData(force = true)

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

        if (arguments?.getBoolean(ARG_FOCUS_SEARCH) == true) {
            b.urlBar.post { focusSearch() }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshHomeData()
    }

    override fun onPause() {
        super.onPause()
        searchHandler.removeCallbacks(searchDebounce)
        suggest?.dismiss()
    }

    /**
     * 统计卡与最近访问：数据签名没变就不重建。
     *
     * [force] = true 时无视签名重建一次（onViewCreated 首次构建用），
     * 另外「容器空了」也视为需要重建，避免任何路径下留下空白卡片。
     */
    private fun refreshHomeData(force: Boolean = false) {
        val b = binding ?: return
        val stats = listOf(
            BookmarkStore.all().size,
            // 「文件下载」统计的是已完成的文件数；把失败/排队中的也算进去会虚高。
            OlfactServices.downloads?.tasks()?.count { it.state == cn.olfact.core.api.DownloadState.DONE } ?: 0,
            OlfactServices.scriptStore?.count() ?: 0
        ).joinToString(",")
        if (force || stats != statsSignature || b.statsCard.childCount == 0) {
            statsSignature = stats
            buildStats(b.statsCard)
        }
        val recent = HistoryStore.recent(3).joinToString("|") { it.url + "\u0000" + it.title }
        if (force || recent != recentSignature || b.recentList.childCount == 0) {
            recentSignature = recent
            renderRecent(b.recentList)
        }
    }

    /** 底栏「刷新」：强制重建统计与最近访问，不用退出页面。 */
    fun refresh() {
        refreshHomeData(force = true)
    }

    /** 底栏标题胶囊 / 首页搜索入口：把焦点交给搜索框。 */
    fun focusSearch() {
        binding?.urlBar?.focusEditor()
    }

    /** 底栏已经在首页时再点「首页」：回到顶部，而不是重建整页。 */
    fun scrollToTop() {
        binding?.scroll?.smoothScrollTo(0, 0)
    }

    /**
     * 首页搜索建议：复用浏览器地址栏的候选弹层（文本提链 / 搜索 / 历史），
     * 与原版 GlobalSearchPopup 的「输入即给候选」一致。
     */
    private fun openHomeSuggestions() {
        val b = binding ?: return
        val text = b.urlBar.text().trim()
        if (text.isEmpty()) {
            suggest?.dismiss()
            return
        }
        if (suggest == null) {
            suggest = UrlSuggestPopup(requireActivity(), b.urlBar).apply {
                onOpen = { url ->
                    b.urlBar.clearFocusCompat()
                    dismiss()
                    Nav.openBrowser(url, reuseCurrent = true)
                }
                onOpenAll = { list ->
                    b.urlBar.clearFocusCompat()
                    dismiss()
                    list.drop(1).forEach {
                        cn.olfact.app.data.BrowserSession.open(it.url, selectNew = false)
                    }
                    list.firstOrNull()?.let { Nav.openBrowser(it.url, reuseCurrent = true) }
                    (activity as? cn.olfact.app.MainActivity)?.refreshBottomBar()
                }
                onSearch = {
                    b.urlBar.clearFocusCompat()
                    dismiss()
                    Nav.openBrowser(SearchEngines.urlFor(it), reuseCurrent = true)
                }
            }
        }
        suggest?.show(text)
    }

    private fun openScanner() {
        suggest?.dismiss()
        runCatching { startActivity(Intent(requireContext(), ScanActivity::class.java)) }
            .onFailure { Nav.toast("相机不可用：${it.message}") }
    }

    private fun buildShortcuts(container: LinearLayout) {
        val items = listOf(
            Shortcut("书签", R.drawable.home_bookmark2) { Nav.push(BookmarksFragment()) },
            Shortcut("下载", R.drawable.home_download) { Nav.push(DownloadListFragment()) },
            Shortcut("历史", R.drawable.home_history2) { Nav.push(HistoryFragment()) },
            Shortcut("设置", R.drawable.home_setting) { Nav.push(SettingsFragment()) },
            Shortcut("小程序", R.drawable.orig_home_miniapp) {
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
                    layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
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

    /** 原版统计卡：三行「标签：数字 + 箭头」，和右侧风景图等高（112dp）。 */
    private fun buildStats(container: LinearLayout) {
        container.removeAllViews()
        val ctx = requireContext()
        val stats = listOf(
            Stat("收藏书签", { BookmarkStore.all().size }) { Nav.push(BookmarksFragment()) },
            Stat("文件下载", {
                OlfactServices.downloads?.tasks()
                    ?.count { it.state == cn.olfact.core.api.DownloadState.DONE } ?: 0
            }) { Nav.push(DownloadListFragment()) },
            Stat("网页插件", { OlfactServices.scriptStore?.count() ?: 0 }) { Nav.push(ScriptFragment()) }
        )
        stats.forEach { stat ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(37)
                setPadding(dp(16), 0, dp(12), 0)
                isClickable = true
                background = ContextCompat.getDrawable(ctx, cn.olfact.core.R.drawable.uikit_ripple_row)
                addView(TextView(ctx).apply {
                    text = "${stat.label}：${stat.count()}"
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f)
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(ImageView(ctx).apply {
                    setImageResource(cn.olfact.core.R.drawable.uikit_ic_chevron_right)
                    imageTintList = ContextCompat.getColorStateList(ctx, R.color.text_hint)
                }, LinearLayout.LayoutParams(dp(16), dp(16)))
                setOnClickListener { stat.action() }
            }
            container.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
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
                onRowClick = { Nav.openBrowser(entry.url, reuseCurrent = true) }
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
        searchHandler.removeCallbacks(searchDebounce)
        suggest?.dismiss()
        suggest = null
        binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_FOCUS_SEARCH = "focus_search"

        fun newInstance(focusSearch: Boolean = false) = HomeFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_FOCUS_SEARCH, focusSearch) }
        }
    }
}
