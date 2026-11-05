package cn.olfact.app.ui.browser

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import cn.olfact.app.R
import cn.olfact.app.data.BrowserSession
import cn.olfact.app.data.HistoryStore
import cn.olfact.app.data.RuntimeFlags
import cn.olfact.app.databinding.FragmentBrowserBinding
import cn.olfact.app.link.SearchEngines
import cn.olfact.app.nav.ChromeAware
import cn.olfact.app.nav.Nav
import cn.olfact.core.api.BrowserSurface
import cn.olfact.core.api.BrowserSurfaceHost
import cn.olfact.core.api.OlfactServices
import cn.olfact.core.uikit.dpF

/**
 * 浏览器主界面：网页铺满 + 底栏（首页 / 刷新 / 标题胶囊 / 标签数 / 菜单）。
 *
 * 与原版 `activity_browser.xml` 一致：**没有常驻顶栏**，地址与页内查找都是贴在底栏上方的
 * 覆盖条，点底栏标题胶囊才出现（原版 `showSearchView(true)` / `search_bg`）。
 * 之前把地址栏、标签数、菜单做成网页上方的一条，既不是原版形态，也挤掉了网页高度。
 *
 * 返回优先级（原版 onBackPressed）：收地址条 → 收页内查找 → 网页后退 → 回首页。
 */
class BrowserFragment : Fragment(R.layout.fragment_browser), ChromeAware {

    override val showsBottomBar: Boolean = true

    /** 底栏高亮在标题胶囊（地址/搜索入口）。 */
    override val barId: String = "search"
    override val barTitle: String? get() = currentTitle()?.takeIf { it.isNotBlank() }

    private var binding: FragmentBrowserBinding? = null
    private var surface: BrowserSurface? = null
    private var suggest: UrlSuggestPopup? = null
    private var findVisible = false
    private var addressVisible = false
    private val handler = Handler(Looper.getMainLooper())
    private val debounce = Runnable { openSuggestions() }

    /** 当前绑定的标签 id。切换标签时由 [showTab] 改写，不需要重建 Fragment。 */
    private var boundTabId: Long = -1L

    private val tab get() = BrowserSession.tabs.firstOrNull { it.id == boundTabId }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentBrowserBinding.bind(view)
        binding = b
        boundTabId = requireArguments().getLong(ARG_TAB)
        // 这两个状态是 Fragment 字段，View 重建后布局默认是 GONE，状态也必须一起重置；
        // 否则「地址条开着时离开页面」再回来，第一次按返回会被一个已经隐藏的条吃掉。
        addressVisible = false
        findVisible = false
        buildAddressBar(b)
        buildSurface(b)
        buildFindBar(b)
        renderBlank(b)

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleBack()
                }
            }
        )

        val url = tab?.url.orEmpty()
        // 首次进入（书签/外链/恢复标签）要记历史；只有 showTab() 换绑标签时不再重复记。
        if (url.isNotBlank()) load(url)
        if (url.isBlank() && arguments?.getBoolean(ARG_FOCUS_URL) == true) {
            showAddressBar()
        }
    }

    override fun onResume() {
        super.onResume()
        // 标签页里可能刚把这个标签关掉；退回浏览器时切到当前标签，而不是停在一个空壳页。
        if (tab == null) {
            view?.post { if (isAdded && tab == null) switchToCurrentTabOrHome() }
            return
        }
        syncBottomBar()
        // 设置页改了夜间网页/无图/字号/UA/JS：回到浏览器时重新应用配置并刷新当前页。
        if (RuntimeFlags.reloadPagesOnResume) {
            RuntimeFlags.reloadPagesOnResume = false
            surface?.refreshConfig()
            surface?.reload()
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(debounce)
        suggest?.dismiss()
    }

    private fun switchToCurrentTabOrHome() {
        val current = BrowserSession.current()
        if (current != null) {
            // 只换绑当前 Fragment，不再 Nav.root 重建整页；否则 Home/返回栈会被一起清掉。
            showTab(current.id)
        } else {
            Nav.openHome()
        }
    }

    /**
     * 换绑到一个已存在的标签（多窗口覆盖层选中标签时调用）。
     *
     * 只换 WebView 的内容，不重建 Fragment，因此 Home / 返回栈 / 滚动位置都能保住。
     */
    fun showTab(id: Long) {
        val target = BrowserSession.tabs.firstOrNull { it.id == id } ?: return
        if (id == boundTabId) {
            syncBottomBar()
            return
        }
        boundTabId = id
        if (findVisible) hideFind()
        hideAddressBar(clearText = false)
        hideError()
        surface?.stopLoading()
        surface?.clearHistory()

        val b = binding ?: return
        val url = target.url
        if (url.isBlank()) {
            b.loading.visibility = View.GONE
            b.urlBar.setProgress(0)
            b.urlBar.setText("")
            b.blankPage.visibility = View.VISIBLE
            renderBlank(b)
            syncBottomBar()
        } else {
            b.blankPage.visibility = View.GONE
            b.urlBar.setText(url)
            load(url, recordHistory = false)
        }
    }

    // ---------------- 地址条覆盖层 ----------------

    private fun buildAddressBar(b: FragmentBrowserBinding) {
        b.urlBar.editable = true
        b.urlBar.setHint(getString(R.string.search_hint))
        b.urlBar.setSecure(OlfactServices.doh?.isProxying() == true)
        b.urlBar.setActionIcon(R.drawable.ic_search)
        b.urlBar.setActionDescription("搜索建议")
        b.urlBar.onTextChanged = { text ->
            handler.removeCallbacks(debounce)
            if (text.isNotBlank() && b.urlBar.editor.hasFocus()) handler.postDelayed(debounce, 200)
        }
        b.urlBar.onSubmit = { text ->
            commit(text)
            hideAddressBar()
        }
        b.urlBar.onActionClick = { openSuggestions() }
        b.addressClose.setOnClickListener { hideAddressBar() }
    }

    /** 底栏标题胶囊 → 展开地址条并聚焦（原版点标题弹 search_bar）。 */
    fun focusUrlBar() {
        showAddressBar(selectAll = true)
    }

    private fun showAddressBar(selectAll: Boolean = false) {
        val b = binding ?: return
        addressVisible = true
        b.addressBar.visibility = View.VISIBLE
        b.urlBar.setText(tab?.url.orEmpty())
        if (selectAll) b.urlBar.editor.setSelection(b.urlBar.text().length)
        b.urlBar.post { b.urlBar.focusEditor() }
    }

    private fun hideAddressBar(clearText: Boolean = true) {
        val b = binding ?: return
        if (!addressVisible) return
        addressVisible = false
        suggest?.dismiss()
        b.urlBar.clearFocusCompat()
        b.addressBar.visibility = View.GONE
        if (clearText) b.urlBar.setText(tab?.url.orEmpty())
    }

    /**
     * 返回优先级：收地址条 → 收页内查找 → 网页后退 → 回首页。
     */
    private fun handleBack() {
        when {
            addressVisible -> hideAddressBar()
            findVisible -> hideFind()
            surface?.canGoBack() == true -> surface?.goBack()
            else -> Nav.pop()
        }
        syncBottomBar()
    }

    private fun buildSurface(b: FragmentBrowserBinding) {
        val s = OlfactServices.browserSurfaceFactory?.create()
            ?: OlfactServices.browserSurface
            ?: DefaultBrowserSurface(requireContext())
        surface = s
        val host = object : BrowserSurfaceHost {
            override fun onProgress(progress: Int) {
                b.loading.visibility = if (progress in 1..99) View.VISIBLE else View.GONE
                b.urlBar.setProgress(progress)
            }

            override fun onTitle(title: String?) {
                tab?.title = title.orEmpty()
                // 用户正在输入地址时不要抢文本（页面标题变化很频繁）
                if (!b.urlBar.editor.hasFocus()) b.urlBar.setText(s.url())
                syncBottomBar()
            }

            override fun onUrl(url: String) {
                tab?.url = url
                if (!b.urlBar.editor.hasFocus()) b.urlBar.setText(url)
            }

            override fun onPageStarted(url: String) {
                tab?.url = url
                if (!b.urlBar.editor.hasFocus()) b.urlBar.setText(url)
                b.blankPage.visibility = View.GONE
                hideError()
            }

            override fun onPageFinished(url: String) {
                tab?.title = s.title().orEmpty()
                syncBottomBar()
            }

            override fun onError(code: Int, description: String, url: String) {
                showError(code, description, url)
            }

            override fun onLongPress(url: String?, title: String?) {
                LongPressSheet.show(requireContext(), url, title) { showFind() }
            }

            override fun onLongPressAt(url: String?, title: String?, x: Float, y: Float) {
                LongPressSheet.showAt(requireContext(), url, title, b.surfaceContainer, x, y) { showFind() }
            }
        }
        // 插到最底层：地址条 / 页内查找 / 空白页都是它的上层覆盖条，
        // 直接 addView（追加到末尾）会让 WebView 盖住它们。
        b.surfaceContainer.addView(
            s.createView(host),
            0,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }

    private var errorView: View? = null

    /** 主文档加载失败：给错误码 + 重试 + 复制网址，不再白屏。 */
    private fun showError(code: Int, description: String, url: String) {
        val b = binding ?: return
        hideError()
        val ctx = requireContext()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.card))
            setPadding(ctx.dpF(28f), ctx.dpF(24f), ctx.dpF(28f), ctx.dpF(24f))
        }
        box.addView(TextView(ctx).apply {
            text = "网页加载失败"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            gravity = Gravity.CENTER
        })
        box.addView(TextView(ctx).apply {
            text = buildString {
                append(description.ifBlank { "网络不可用或页面无法访问" })
                if (code != 0) append("（错误码 ").append(code).append("）")
            }
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            gravity = Gravity.CENTER
            setPadding(0, ctx.dpF(10f), 0, ctx.dpF(22f))
        })

        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        actions.addView(TextView(ctx).apply {
            text = "重试"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(ctx.dpF(20f), ctx.dpF(9f), ctx.dpF(20f), ctx.dpF(9f))
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_button_primary)
            setOnClickListener { hideError(); surface?.reload() }
        })
        actions.addView(TextView(ctx).apply {
            text = "复制网址"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_accent))
            gravity = Gravity.CENTER
            setPadding(ctx.dpF(20f), ctx.dpF(9f), ctx.dpF(20f), ctx.dpF(9f))
            setOnClickListener {
                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
                Nav.toast("网址已复制")
            }
        })
        box.addView(actions)

        // 放在 WebView 之上、覆盖条之下：错误页不应挡住地址栏/页内查找。
        b.surfaceContainer.addView(
            box,
            1,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        errorView = box
    }

    private fun hideError() {
        errorView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        errorView = null
    }

    private fun buildFindBar(b: FragmentBrowserBinding) {
        b.findClose.setOnClickListener { hideFind() }
        b.findInput.setOnEditorActionListener { _, _, _ ->
            surface?.findAll(b.findInput.text.toString())
            true
        }
        b.findPrev.setOnClickListener { surface?.findNext(false) }
        b.findNext.setOnClickListener { surface?.findNext(true) }
    }

    private fun renderBlank(b: FragmentBrowserBinding) {
        val blank = tab?.url.isNullOrBlank()
        b.blankPage.visibility = if (blank) View.VISIBLE else View.GONE
        if (!blank) return
        val links = listOf(
            "百度" to "https://www.baidu.com",
            "必应" to "https://cn.bing.com",
            "知乎" to "https://www.zhihu.com",
            "GitHub" to "https://github.com"
        )
        b.quickLinks.removeAllViews()
        links.chunked(2).forEach { pair ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            pair.forEach { (name, url) ->
                row.addView(TextView(requireContext()).apply {
                    text = name
                    gravity = Gravity.CENTER
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                    setBackgroundResource(R.drawable.bg_button_ghost)
                    setPadding(dp(18), dp(10), dp(18), dp(10))
                    setOnClickListener { load(url) }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(10)
                })
            }
            b.quickLinks.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) })
        }
    }

    private fun openSuggestions() {
        val b = binding ?: return
        val text = b.urlBar.text()
        if (text.isBlank()) return
        if (suggest == null) {
            // 地址条贴在底栏上方，候选要往上弹，否则会落到屏幕外。
            suggest = UrlSuggestPopup(requireActivity(), b.urlBar, above = true).apply {
                onOpen = { url ->
                    b.urlBar.setText(url)
                    load(url)
                    hideAddressBar()
                }
                onOpenAll = { list ->
                    // 其余候选进后台标签，不能改 currentId，否则地址栏还是 A、
                    // 会话已经指向最后一个后台标签，切标签/加书签都会错位。
                    list.drop(1).forEach { BrowserSession.open(it.url, selectNew = false) }
                    list.firstOrNull()?.let {
                        b.urlBar.setText(it.url)
                        load(it.url)
                    }
                    hideAddressBar()
                    syncBottomBar()
                    Nav.toast("已打开 ${list.size} 个标签页")
                }
                onSearch = { text ->
                    commit(text)
                    hideAddressBar()
                }
            }
        }
        suggest?.show(text)
    }

    /** 输入框提交：是链接就直接开，否则搜索。 */
    private fun commit(text: String) {
        if (text.isBlank()) return
        val url = SearchEngines.urlFor(text)
        load(url)
    }

    fun load(url: String, recordHistory: Boolean = true) {
        binding?.blankPage?.visibility = View.GONE
        surface?.loadUrl(url)
        tab?.url = url
        if (recordHistory) HistoryStore.record(requireContext(), url, url)
        binding?.urlBar?.setText(url)
        syncBottomBar()
    }

    fun reload() {
        surface?.reload()
        syncBottomBar()
    }

    /**
     * 原版第二格：能前进就先前进，否则刷新。
     * 底栏图标也按这个状态在「刷新 / 前进」之间切换。
     */
    fun refreshOrForward() {
        if (surface?.canGoForward() == true) surface?.goForward() else surface?.reload()
        syncBottomBar()
    }

    /** 底栏变体「前进后退」里的后退格。 */
    fun goBack() {
        if (surface?.canGoBack() == true) surface?.goBack() else Nav.pop()
        syncBottomBar()
    }

    fun canGoForward(): Boolean = surface?.canGoForward() == true

    /** 底栏胶囊要显示的标题（当前标签页）：标题为空时退回网址，别显示成「主页」。 */
    fun currentTitle(): String? =
        tab?.title?.takeIf { it.isNotBlank() }
            ?: surface?.title()?.takeIf { it.isNotBlank() }
            ?: tab?.url?.takeIf { it.isNotBlank() }

    private fun syncBottomBar() {
        (activity as? cn.olfact.app.MainActivity)?.refreshBottomBar()
    }

    private fun dp(v: Int) = requireContext().dpF(v.toFloat())

    // ---------------- 页内查找 ----------------

    private fun showFind() {
        val b = binding ?: return
        hideAddressBar(clearText = false)
        findVisible = true
        b.findBar.visibility = View.VISIBLE
        b.findInput.requestFocus()
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager
        imm?.showSoftInput(b.findInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideFind() {
        val b = binding ?: return
        findVisible = false
        b.findBar.visibility = View.GONE
        b.findInput.setText("")
        surface?.clearFind()
        b.findInput.clearFocus()
    }

    override fun onDestroyView() {
        handler.removeCallbacks(debounce)
        suggest?.dismiss()
        suggest = null
        surface?.destroy()
        surface = null
        binding = null
        // 与 onViewCreated 的重置配对：View 没了，视觉状态也不该留着。
        addressVisible = false
        findVisible = false
        super.onDestroyView()
    }

    companion object {
        private const val ARG_TAB = "tab_id"
        private const val ARG_FOCUS_URL = "focus_url"

        fun newInstance(tabId: Long, focusUrl: Boolean = false) = BrowserFragment().apply {
            arguments = Bundle().apply {
                putLong(ARG_TAB, tabId)
                putBoolean(ARG_FOCUS_URL, focusUrl)
            }
        }
    }
}
