package cn.olfact.app.ui.browser

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import cn.olfact.app.R
import cn.olfact.app.data.AppSettings
import cn.olfact.app.data.BrowserSession
import cn.olfact.app.data.HistoryStore
import cn.olfact.app.data.K
import cn.olfact.app.data.RuntimeFlags
import cn.olfact.app.databinding.FragmentBrowserBinding
import cn.olfact.app.link.LinkExtractor
import cn.olfact.app.link.SearchEngines
import cn.olfact.app.menu.MainMenuSheet
import cn.olfact.app.nav.ChromeAware
import cn.olfact.app.nav.Nav
import cn.olfact.app.ui.common.Dialogs
import cn.olfact.core.api.BrowserSurface
import cn.olfact.core.api.BrowserSurfaceHost
import cn.olfact.core.api.OlfactServices
import cn.olfact.core.uikit.dpF

/**
 * 浏览器主界面：地址栏 + 渲染器 + 页内查找。
 *
 * 渲染器优先用 B2 注册的 [BrowserSurface]；没有则用 [DefaultBrowserSurface] 兜底，
 * 保证「设为默认浏览器 → 点外部链接 → 能打开」这条链路现在就能验收。
 */
class BrowserFragment : Fragment(R.layout.fragment_browser), ChromeAware {

    /** 原版浏览器页有底栏：首页 / 刷新 / 标题胶囊 / 标签数 / 菜单。 */
    override val showsBottomBar: Boolean = true
    override val barId: String = "main"

    private var binding: FragmentBrowserBinding? = null
    private var surface: BrowserSurface? = null
    private var suggest: UrlSuggestPopup? = null
    private var findVisible = false
    private val handler = Handler(Looper.getMainLooper())
    private val debounce = Runnable { openSuggestions() }

    private val tabId: Long get() = requireArguments().getLong(ARG_TAB)

    private val tab get() = BrowserSession.tabs.firstOrNull { it.id == tabId }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentBrowserBinding.bind(view)
        binding = b
        buildChrome(b)
        buildSurface(b)
        buildFindBar(b)
        renderBlank(b)

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val s = surface
                    when {
                        findVisible -> hideFind()
                        s != null && s.canGoBack() -> s.goBack()
                        else -> {
                            isEnabled = false
                            Nav.pop()
                        }
                    }
                }
            }
        )

        val url = tab?.url.orEmpty()
        if (url.isNotBlank()) load(url)
    }

    override fun onResume() {
        super.onResume()
        // 设置页改了夜间网页/无图/字号/UA/JS：回到浏览器时自动刷新当前页。
        if (RuntimeFlags.reloadPagesOnResume) {
            RuntimeFlags.reloadPagesOnResume = false
            surface?.reload()
        }
    }

    private fun buildChrome(b: FragmentBrowserBinding) {
        b.urlBar.editable = true
        b.urlBar.setHint(getString(R.string.search_hint))
        b.urlBar.setSecure(OlfactServices.doh?.isProxying() == true)
        b.urlBar.onTextChanged = { text ->
            handler.removeCallbacks(debounce)
            if (text.isNotBlank() && b.urlBar.editor.hasFocus()) handler.postDelayed(debounce, 200)
        }
        b.urlBar.onSubmit = { text ->
            commit(text)
            b.urlBar.clearFocusCompat()
        }
        b.urlBar.onBarClick = { b.urlBar.focusEditor() }
        b.urlBar.onActionClick = { openSuggestions() }

        b.back.setOnClickListener {
            val s = surface
            if (s != null && s.canGoBack()) s.goBack() else Nav.pop()
        }
        b.menu.setOnClickListener { MainMenuSheet.show(requireActivity() as cn.olfact.app.MainActivity) }
        b.tabCountBox.setOnClickListener { Nav.push(cn.olfact.app.ui.TabsFragment()) }
        updateTabCount()
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
                b.urlBar.setText(s.url())
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
                updateTabCount()
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
        b.surfaceContainer.addView(
            s.createView(host),
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

        b.surfaceContainer.addView(
            box,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        errorView = box
    }

    private fun hideError() {
        errorView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        errorView = null
    }

    private fun buildFindBar(b: FragmentBrowserBinding) {        b.findClose.setOnClickListener { hideFind() }
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
            suggest = UrlSuggestPopup(requireActivity(), b.urlBar).apply {
                onOpen = { url ->
                    b.urlBar.setText(url)
                    load(url)
                }
                onOpenAll = { list ->
                    list.drop(1).forEach { BrowserSession.open(it.url) }
                    list.firstOrNull()?.let {
                        b.urlBar.setText(it.url)
                        load(it.url)
                    }
                    updateTabCount()
                    Nav.toast("已打开 ${list.size} 个标签页")
                }
                onSearch = { commit(it) }
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

    fun load(url: String) {
        binding?.blankPage?.visibility = View.GONE
        surface?.loadUrl(url)
        tab?.url = url
        HistoryStore.record(requireContext(), url, url)
        binding?.urlBar?.setText(url)
        syncBottomBar()
    }

    fun reload() {
        surface?.reload()
    }

    /** 底栏胶囊要显示的标题（当前标签页）。 */
    fun currentTitle(): String? =
        tab?.title?.takeIf { it.isNotBlank() } ?: surface?.title()

    private fun syncBottomBar() {
        (activity as? cn.olfact.app.MainActivity)?.refreshBottomBar()
    }

    private fun dp(v: Int) = requireContext().dpF(v.toFloat())

    private fun updateTabCount() {
        binding?.tabCount?.text = BrowserSession.tabs.size.toString()
    }

    // ---------------- 页内查找 ----------------

    private fun showFind() {
        val b = binding ?: return
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
        super.onDestroyView()
    }

    companion object {
        private const val ARG_TAB = "tab_id"
        fun newInstance(tabId: Long) = BrowserFragment().apply {
            arguments = Bundle().apply { putLong(ARG_TAB, tabId) }
        }
    }
}
