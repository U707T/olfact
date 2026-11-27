package cn.olfact.app.ui.browser

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import androidx.core.widget.doAfterTextChanged
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

    private val fileChooser = SingleFlightFileChooser<List<android.net.Uri>>()
    private var filePickerAllowMultiple = false
    private val filePickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val chooserContext = context
        val packageName = chooserContext?.packageName
        val ownProvider = packageName?.let { "$it.fileprovider" }
        val callbackData = if (result.resultCode == android.app.Activity.RESULT_OK && chooserContext != null) {
            val data = result.data
            buildList {
                data?.clipData?.let { clip ->
                    repeat(clip.itemCount) { index -> clip.getItemAt(index).uri?.let(::add) }
                }
                data?.data?.let(::add)
            }.distinct()
                .filter { uri ->
                    chooserUriAllowed(
                        scheme = uri.scheme,
                        authority = uri.authority,
                        ownAuthority = ownProvider,
                        readPermissionGranted = chooserContext.checkUriPermission(
                            uri,
                            android.os.Process.myPid(),
                            android.os.Process.myUid(),
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    )
                }
                .let { if (filePickerAllowMultiple) it else it.take(1) }
                .takeIf { it.isNotEmpty() }
        } else null
        filePickerAllowMultiple = false
        fileChooser.complete(callbackData)
    }

    override val showsBottomBar: Boolean = true

    /** 底栏高亮在标题胶囊（地址/搜索入口）。 */
    override val barId: String = "search"
    override val barTitle: String? get() = currentTitle()?.takeIf { it.isNotBlank() }

    private var binding: FragmentBrowserBinding? = null
    private var surface: BrowserSurface? = null
    private var surfaceHost: BrowserSurfaceHost? = null
    private var suggest: UrlSuggestPopup? = null
    private var findVisible = false
    private var findWebView: WebView? = null
    private var findRequest = 0
    private var addressVisible = false
    private val handler = Handler(Looper.getMainLooper())
    private val debounce = Runnable { openSuggestions() }

    /** 当前绑定的标签 id。切换标签时由 [showTab] 改写，不需要重建 Fragment。 */
    private var boundTabId: Long = -1L

    /** 本次 View 是否复用了缓存里的 WebView（复用时不要再 load，页面已经在那儿）。 */
    private var surfaceReused = false

    private val tab get() = BrowserSession.tabs.firstOrNull { it.id == boundTabId }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentBrowserBinding.bind(view)
        binding = b
        // 只在第一次创建时用参数里的标签；`showTab()` 之后 View 可能被销毁重建
        // （进设置/标签页），此时必须保住当前绑定的标签，否则会退回创建时的那个。
        // 进程被杀后重建则靠 savedInstanceState 里的值。
        val restored = savedInstanceState?.getLong(KEY_BOUND_TAB, -1L) ?: -1L
        val initial = if (restored > 0L) restored else boundTabId
        boundTabId = resolveBoundTabId(initial, requireArguments().getLong(ARG_TAB))
        if (tab == null) boundTabId = BrowserSession.ensureTab().id
        BrowserSession.select(boundTabId)
        SurfaceCache.retainTabs(BrowserSession.tabs.mapTo(mutableSetOf()) { it.id })
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
        // 复用缓存的 WebView 时页面已经加载好，再 load 一次等于白重载。
        if (url.isNotBlank()) {
            if (surfaceReused && surface?.url() == url) {
                b.urlBar.setText(url)
            } else {
                load(url)
            }
        }
        if (url.isBlank() && arguments?.getBoolean(ARG_FOCUS_URL) == true && initial <= 0L) {
            b.urlBar.post { if (binding === b && Nav.current() === this && tab?.url.isNullOrBlank()) showAddressBar() }
        }
    }

    override fun onResume() {
        super.onResume()
        // 标签页里可能刚把这个标签关掉；退回浏览器时切到当前标签，而不是停在一个空壳页。
        if (tab == null) {
            showTab(BrowserSession.ensureTab().id)
        }
        surfaceHost?.let { surface?.attachHost(it) }
        syncBottomBar()
        // 设置页改了夜间网页/无图/字号/UA/JS：回到浏览器时重新应用配置并刷新当前页。
        if (RuntimeFlags.reloadPagesOnResume) {
            RuntimeFlags.reloadPagesOnResume = false
            SurfaceCache.destroyCurrent()
            surface?.refreshConfig()
            if (!tab?.url.isNullOrBlank()) surface?.reload()
        }
    }

    override fun onPause() {
        super.onPause()
        // Launching the system file picker pauses this Activity; keep its pending callback alive.
        fileChooser.onActivityPause()
        surface?.attachHost(null)
        handler.removeCallbacks(debounce)
        suggest?.dismiss()
    }

    /**
     * 换绑到一个已存在的标签（多窗口覆盖层选中标签时调用）。
     *
     * 只换 WebView 的内容，不重建 Fragment，因此 Home / 返回栈 / 滚动位置都能保住。
     */
    fun showTab(id: Long, recordHistory: Boolean = false, focusBlank: Boolean = false) {
        val target = BrowserSession.tabs.firstOrNull { it.id == id } ?: return
        if (id == boundTabId) {
            BrowserSession.select(id)
            if (focusBlank && target.url.isBlank()) showAddressBar()
            syncBottomBar()
            return
        }
        if (findVisible) hideFind()
        hideAddressBar(clearText = false)
        hideError()
        val b = binding
        releaseSurface()
        boundTabId = id
        BrowserSession.select(id)
        SurfaceCache.retainTabs(BrowserSession.tabs.mapTo(mutableSetOf()) { it.id })
        if (b == null) return
        buildSurface(b)
        val url = target.url
        if (url.isBlank()) {
            b.loading.visibility = View.GONE
            b.urlBar.setProgress(0)
            b.urlBar.setText("")
            b.blankPage.visibility = View.VISIBLE
            renderBlank(b)
            if (focusBlank) showAddressBar()
            syncBottomBar()
        } else {
            b.blankPage.visibility = View.GONE
            b.urlBar.setText(surface?.url() ?: url)
            if (!surfaceReused || surface?.url() != url) load(url, recordHistory)
            syncBottomBar()
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
            if (addressVisible && text.isNotBlank() && b.urlBar.editor.hasFocus()) {
                handler.postDelayed(debounce, 200)
            } else {
                suggest?.dismiss()
            }
        }
        b.urlBar.onEditorFocusChanged = { focused ->
            if (!focused) {
                handler.removeCallbacks(debounce)
                suggest?.dismiss()
            }
        }
        b.urlBar.onSubmit = ::commit
        b.urlBar.onActionClick = { openSuggestions() }
        b.addressClose.setOnClickListener { hideAddressBar() }
    }

    /** 底栏标题胶囊 → 展开地址条并聚焦（原版点标题弹 search_bar）。 */
    fun focusUrlBar() {
        showAddressBar(selectAll = true)
    }

    private fun showAddressBar(selectAll: Boolean = false) {
        val b = binding ?: return
        if (findVisible) hideFind()
        if (!addressVisible) {
            b.urlBar.setText(tab?.url.orEmpty())
            addressVisible = true
            b.addressBar.visibility = View.VISIBLE
        }
        b.urlBar.focusEditor()
        if (selectAll) b.urlBar.editor.selectAll()
    }

    private fun hideAddressBar(clearText: Boolean = true) {
        val b = binding ?: return
        if (!addressVisible) return
        addressVisible = false
        handler.removeCallbacks(debounce)
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
        // 先从缓存里找这个标签的 WebView：找到就直接挂回新容器，页面不重载。
        val cached = SurfaceCache.take(boundTabId)
        surfaceReused = cached != null
        val s = cached ?: run {
            OlfactServices.browserSurfaceFactory?.create()
                ?: OlfactServices.browserSurface
                ?: DefaultBrowserSurface(requireContext())
        }
        surface = s
        val host = object : BrowserSurfaceHost {
            override fun onProgress(progress: Int) {
                b.loading.visibility = if (progress in 1..99) View.VISIBLE else View.GONE
                b.urlBar.setProgress(progress)
            }

            override fun onTitle(title: String?) {
                tab?.title = title.orEmpty()
                // 用户正在输入地址时不要抢文本（页面标题变化很频繁）
                if (!addressVisible) b.urlBar.setText(s.url())
                syncBottomBar()
            }

            override fun onUrl(url: String) {
                tab?.url = url
                if (!addressVisible) b.urlBar.setText(url)
            }

            override fun onPageStarted(url: String) {
                if (findVisible) hideFind()
                tab?.url = url
                if (!addressVisible) b.urlBar.setText(url)
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

            override fun onChooseFiles(
                mimeTypes: List<String>,
                allowMultiple: Boolean,
                onResult: (List<android.net.Uri>?) -> Unit
            ) {
                if (!fileChooser.begin(onResult)) return
                filePickerAllowMultiple = allowMultiple
                val accepted = browserAcceptMimeTypes(mimeTypes)
                val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
                    addCategory(android.content.Intent.CATEGORY_OPENABLE)
                    type = accepted.singleOrNull() ?: "*/*"
                    if (accepted.size > 1) putExtra(android.content.Intent.EXTRA_MIME_TYPES, accepted.toTypedArray())
                    putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    filePickerLauncher.launch(android.content.Intent.createChooser(intent, "选择文件"))
                } catch (_: Exception) {
                    filePickerAllowMultiple = false
                    fileChooser.complete(null)
                    Nav.toast("无法打开文件选择器")
                }
            }

            override fun onCancelFileChooser() {
                fileChooser.cancelPending()
            }

            override fun onLongPress(url: String?, title: String?) {
                LongPressSheet.show(requireContext(), url, title) { showFind() }
            }

            override fun onLongPressAt(url: String?, title: String?, x: Float, y: Float) {
                LongPressSheet.showAt(requireContext(), url, title, b.surfaceContainer, x, y) { showFind() }
            }
        }
        // 复用时先换宿主：旧 host 闭包引用的是已销毁的 binding。
        surfaceHost = host
        s.attachHost(host)
        val surfaceView = s.view() ?: s.createView(host)
        s.refreshConfig()
        // 插到最底层：地址条 / 页内查找 / 空白页都是它的上层覆盖条，
        // 直接 addView（追加到末尾）会让 WebView 盖住它们。
        b.surfaceContainer.addView(
            surfaceView,
            0,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }

    private var errorView: View? = null

    /** 主文档加载失败：给错误码 + 重试 + 复制网址，不再白屏。 */
    private fun showError(code: Int, description: String, url: String) {
        val b = binding ?: return
        hideError()
        if (findVisible) hideFind()
        b.loading.visibility = View.GONE
        b.urlBar.setProgress(0)
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
        box.announceForAccessibility("网页加载失败")
    }

    private fun hideError() {
        errorView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        errorView = null
    }

    private fun buildFindBar(b: FragmentBrowserBinding) {
        b.findClose.setOnClickListener { hideFind() }
        b.findInput.doAfterTextChanged { text ->
            if (findVisible) searchInPage(b, text.toString())
        }
        b.findInput.setOnEditorActionListener { _, actionId, event ->
            val submit = actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (submit) hideFindKeyboard(b)
            submit
        }
        updateFindStatus(b, "", false)
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
        if (!addressVisible || !b.urlBar.editor.hasFocus() || text.isBlank()) {
            suggest?.dismiss()
            return
        }
        if (suggest == null) {
            // 地址条贴在底栏上方，候选要往上弹，否则会落到屏幕外。
            suggest = UrlSuggestPopup(requireActivity(), b.urlBar, above = true).apply {
                onOpen = { url ->
                    navigate(url)
                }
                onOpenAll = { list ->
                    // 其余候选进后台标签，不能改 currentId，否则地址栏还是 A、
                    // 会话已经指向最后一个后台标签，切标签/加书签都会错位。
                    list.drop(1).forEach { BrowserSession.openDerived(boundTabId, it.url, selectNew = false) }
                    list.firstOrNull()?.let {
                        navigate(it.url)
                    }
                    syncBottomBar()
                    Nav.toast("已打开 ${list.size} 个标签页")
                }
                onSearch = { text -> navigate(SearchEngines.searchUrlFor(text)) }
            }
        }
        suggest?.show(text)
    }

    /** 输入框提交：是链接就直接开，否则搜索。 */
    private fun commit(text: String) {
        if (text.isBlank()) return
        navigate(SearchEngines.urlFor(text))
    }

    fun navigate(url: String) {
        hideAddressBar(clearText = false)
        if (findVisible) hideFind()
        load(url)
    }

    fun load(url: String, recordHistory: Boolean = true) {
        hideError()
        binding?.blankPage?.visibility = View.GONE
        tab?.let {
            if (it.url != url) it.title = ""
            it.url = url
        }
        surface?.loadUrl(url)
        // 无痕标签不写历史：之前只有渲染层的 onPageVisited 判断了 incognito，
        // 地址栏 / 快捷入口 / 书签走的这条 load() 照样落一条记录。
        if (recordHistory && tab?.incognito == false) {
            HistoryStore.record(requireContext(), url, url)
        }
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
    fun goBack() = handleBack()

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
        if (tab?.url.isNullOrBlank() || errorView != null) {
            Nav.toast("当前没有可查找的网页")
            return
        }
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
        clearFindListener()
        surface?.clearFind()
        hideFindKeyboard(b)
        b.findInput.clearFocus()
        b.findBar.visibility = View.GONE
        b.findInput.setText("")
        updateFindStatus(b, "", false)
    }

    private fun searchInPage(b: FragmentBrowserBinding, query: String) {
        clearFindListener()
        val s = surface ?: return
        if (query.isBlank()) {
            s.clearFind()
            updateFindStatus(b, "", false)
            return
        }
        // The in-app surfaces both expose a WebView; leave navigation available for a non-WebView fallback.
        val web = s.view() as? WebView
        updateFindStatus(b, if (web == null) "" else "查找中", web == null)
        val request = findRequest
        if (web != null) {
            findWebView = web
            web.setFindListener { active, count, done ->
                if (done && request == findRequest && findVisible && findWebView === web && binding === b) {
                    val status = if (count == 0) "无匹配" else "${active + 1}/$count"
                    updateFindStatus(b, status, count > 0)
                }
            }
        }
        s.findAll(query)
    }

    private fun updateFindStatus(b: FragmentBrowserBinding, text: String, canMove: Boolean) {
        b.findCount.text = text
        for (button in listOf(b.findPrev, b.findNext)) {
            button.isEnabled = canMove
            button.alpha = if (canMove) 1f else 0.38f
        }
    }

    private fun clearFindListener() {
        findRequest++
        findWebView?.takeIf { it === surface?.view() }?.setFindListener(null)
        findWebView = null
    }

    private fun hideFindKeyboard(b: FragmentBrowserBinding) {
        val imm = requireContext().getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(b.findInput.windowToken, 0)
    }

    override fun onDestroyView() {
        handler.removeCallbacks(debounce)
        suggest?.dismiss()
        suggest = null
        releaseSurface()
        binding = null
        errorView = null
        // 与 onViewCreated 的重置配对：View 没了，视觉状态也不该留着。
        addressVisible = false
        findVisible = false
        super.onDestroyView()
    }

    private fun releaseSurface() {
        fileChooser.cancelPending()
        clearFindListener()
        val s = surface ?: return
        if (findVisible) s.clearFind()
        surface = null
        surfaceHost = null
        s.attachHost(null)
        val view = s.view()
        (view?.parent as? ViewGroup)?.removeView(view)
        if (tab != null) SurfaceCache.offer(boundTabId, s) else s.destroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 进程被杀/配置变更重建时，仍要回到用户当时看的那个标签。
        outState.putLong(KEY_BOUND_TAB, boundTabId)
    }

    companion object {
        private const val ARG_TAB = "tab_id"
        private const val ARG_FOCUS_URL = "focus_url"
        private const val KEY_BOUND_TAB = "bound_tab"

        fun newInstance(tabId: Long, focusUrl: Boolean = false) = BrowserFragment().apply {
            arguments = Bundle().apply {
                putLong(ARG_TAB, tabId)
                putBoolean(ARG_FOCUS_URL, focusUrl)
            }
        }
    }
}
