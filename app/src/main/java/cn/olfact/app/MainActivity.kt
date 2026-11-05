package cn.olfact.app

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import cn.olfact.app.data.AppSettings
import cn.olfact.app.data.AppLog
import cn.olfact.app.data.BrowserSession
import cn.olfact.app.data.BookmarkStore
import cn.olfact.app.data.FavoriteStore
import cn.olfact.app.data.HistoryStore
import cn.olfact.app.data.K
import cn.olfact.app.databinding.ActivityMainBinding
import cn.olfact.app.link.LinkExtractor
import cn.olfact.app.link.SearchEngines
import cn.olfact.app.menu.MainMenuSheet
import cn.olfact.app.nav.ChromeAware
import cn.olfact.app.nav.Nav
import cn.olfact.app.ui.HomeFragment
import cn.olfact.app.ui.TabsFragment
import cn.olfact.app.ui.browser.BrowserFragment
import cn.olfact.core.backup.PrivacyCleaner
import cn.olfact.core.uikit.BottomBar

/**
 * 唯一的 Activity：首页 / 浏览 / 设置 / 播放（PiP）都在这里。
 *
 * 它同时是「默认浏览器」的入口：冷启动从 Intent 取 URL，多链接自动拆成多标签。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        toast(if (result.resultCode == RESULT_OK) "已设为默认浏览器" else "未授予默认浏览器角色")
    }

    private val notifyPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        AppLog.i("notify", "通知权限：$granted")
    }

    /** 字体缩放：设置页改完 recreate 后由这里生效（1.0 = 100%）。 */
    override fun attachBaseContext(newBase: Context) {
        val scale = runCatching { AppSettings.get(K.FONT_SCALE) / 100f }.getOrDefault(1f)
        if (scale != 1f) {
            val config = android.content.res.Configuration(newBase.resources.configuration)
            config.fontScale = scale
            super.attachBaseContext(newBase.createConfigurationContext(config))
        } else {
            super.attachBaseContext(newBase)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyInsets()

        Nav.attach(this, R.id.container)
        // 底栏角标、标签页列表与「1」的初始状态保持一致：会话至少有一个标签页。
        BrowserSession.ensureTab()
        setupBottomBar()
        observeChrome()
        applyEyeCare()
        requestNotifyPermissionOnce()

        if (savedInstanceState == null) {
            // restore 内部会替换掉启动时的唯一空白页，不会多出一个空标签。
            BrowserSession.restoreIfNeeded(this)
            // 先落一个首页作为根页面，再处理外部链接/下载通知。
            // 否则冷启动直接 push 浏览器时，返回栈只有浏览器一项，按返回会把容器清空。
            Nav.openHome()
            supportFragmentManager.executePendingTransactions()
            handleIntent(intent)
            AppLog.i("app", "启动完成，模块：${cn.olfact.core.api.OlfactServices.modules().joinToString { "${it.first}=${it.second}" }}")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // 设置页改「底部栏样式」后回到主界面立即生效，不必重启（含四种槽位变体）。
        val variant = AppSettings.get(K.BOTTOM_STYLE)
        if (currentBottomStyle != null && currentBottomVariant != variant) setupBottomBar()
        applyChromeForCurrent()
    }

    override fun onPause() {
        super.onPause()
        BrowserSession.save(this)
        // 历史/书签是 debounce 落盘；退到后台时补一次，降低被杀丢数据的概率。
        HistoryStore.flush(this)
        BookmarkStore.flush(this)
        FavoriteStore.flush(this)
    }

    override fun onDestroy() {
        // 静态弹层可能持有 Activity，退出前统一收起
        MainMenuSheet.dismiss()
        cn.olfact.app.ui.ai.AiSheet.dismissCurrent()
        cn.olfact.app.ui.player.SnifferSheet.dismiss()
        cn.olfact.app.ui.download.AddDownloadSheet.dismiss()
        cn.olfact.app.ui.browser.LongPressSheet.dismiss()

        // 「退出时清除数据」：只在本 Activity 真被销毁（返回键/划掉）时清，
        // 配置变更（旋转、切深色）不能误清。
        Nav.detach(this)

        if (isFinishing && ::binding.isInitialized && AppSettings.get(K.PRIVACY_CLEAR_ON_EXIT)) {
            val cleared = runCatching {
                PrivacyCleaner(this).clearAll(
                    listOf(
                        PrivacyCleaner.Cleanable {
                            HistoryStore.clear(this)
                            HistoryStore.items.size
                        }
                    )
                )
            }
            AppLog.i("privacy", "退出清数据：$cleared")
        }
        super.onDestroy()
    }

    private var fullscreen = false
    private var bottomInset = 0
    private var currentBottomStyle: BottomBar.Style? = null
    private var currentBottomVariant: String? = null
    private var eyeCareOverlay: android.view.View? = null

    /** 主菜单「全屏」：隐藏/恢复系统栏（上滑可临时唤出）。 */
    fun toggleFullscreen() {
        fullscreen = !fullscreen
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (fullscreen) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        AppLog.i("ui", "全屏：$fullscreen")
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            bottomInset = bars.bottom
            binding.container.updatePadding(top = bars.top)
            binding.bottomBar.updatePadding(bottom = bars.bottom)
            // 底栏隐藏时（设置/列表页）内容区要自己让出导航栏/手势条，否则最后一行被挡住。
            updateContainerBottomInset()
            insets
        }
    }

    private fun updateContainerBottomInset() {
        if (!::binding.isInitialized) return
        val visible = binding.bottomBar.visibility == android.view.View.VISIBLE
        binding.container.updatePadding(bottom = if (visible) 0 else bottomInset)
    }

    private fun setupBottomBar() {
        val variant = AppSettings.get(K.BOTTOM_STYLE)
        val style = if (variant == "icon") BottomBar.Style.ICON_ONLY else BottomBar.Style.PILL
        currentBottomStyle = style
        currentBottomVariant = variant
        // 五槽对齐原版 view_stub_browser_bottom1..4：间距/尺寸照抄，
        // 只按设置切换槽位顺序（原版就是用 4 份 layout 表达 4 种底栏）。
        val items = when (variant) {
            // 原版 bottom3：第一格后退、第二格前进（阅读/沉浸场景）
            "back" -> listOf(
                BottomBar.Item("back", R.drawable.orig_b_back, description = "后退", marginStart = 10f),
                BottomBar.Item(
                    "refresh", R.drawable.orig_b_forward, description = "前进或刷新",
                    marginStart = 15f, marginEnd = 18f
                ),
                BottomBar.Item(
                    "search", label = "主页", kind = BottomBar.Kind.PILL,
                    description = "地址与搜索", marginStart = 5f, marginEnd = 5f
                ),
                BottomBar.Item("tabs", kind = BottomBar.Kind.COUNT, description = "标签页", marginStart = 18f),
                BottomBar.Item("menu", R.drawable.orig_b_menu, description = "菜单", marginStart = 15f, marginEnd = 10f)
            )
            // 原版 bottom4：标签数在最左，首页在右
            "tabs_left" -> listOf(
                BottomBar.Item("tabs", kind = BottomBar.Kind.COUNT, description = "标签页", marginStart = 10f),
                BottomBar.Item(
                    "refresh", R.drawable.orig_b_refresh, description = "刷新或前进",
                    marginStart = 15f, marginEnd = 18f
                ),
                BottomBar.Item(
                    "search", label = "主页", kind = BottomBar.Kind.PILL,
                    description = "地址与搜索", marginStart = 5f, marginEnd = 5f
                ),
                BottomBar.Item("home", R.drawable.orig_b_home, description = "首页", marginStart = 18f),
                BottomBar.Item("menu", R.drawable.orig_b_menu, description = "菜单", marginStart = 15f, marginEnd = 10f)
            )
            else -> listOf(
                BottomBar.Item("home", R.drawable.orig_b_home, description = "首页", marginStart = 10f),
                BottomBar.Item(
                    "refresh", R.drawable.orig_b_refresh, description = "刷新或前进",
                    marginStart = 15f, marginEnd = 18f
                ),
                BottomBar.Item(
                    "search", label = "主页", kind = BottomBar.Kind.PILL,
                    description = "地址与搜索", marginStart = 5f, marginEnd = 5f
                ),
                BottomBar.Item("tabs", kind = BottomBar.Kind.COUNT, description = "标签页", marginStart = 18f),
                BottomBar.Item("menu", R.drawable.orig_b_menu, description = "菜单", marginStart = 15f, marginEnd = 10f)
            )
        }
        binding.bottomBar.configure(items, style)
        binding.bottomBar.onSelect = { id ->
            when (id) {
                "home" -> {
                    val home = Nav.current() as? HomeFragment
                    // 已在首页：回到顶部；否则回首页根页面
                    if (home != null) home.scrollToTop() else Nav.goHome()
                }
                "back" -> (Nav.current() as? BrowserFragment)?.goBack() ?: Nav.pop()                "refresh" -> when (val f = Nav.current()) {
                    is BrowserFragment -> f.refreshOrForward()
                    is HomeFragment -> f.refresh()
                    is TabsFragment -> f.refresh()
                }
                // 原版标题胶囊 = 地址/搜索入口：把焦点交给当前页的输入框，而不是打开标签页
                "search" -> openSearch()
                "tabs" -> Nav.toggle(TabsFragment())
                "menu" -> MainMenuSheet.toggle(this)
            }
        }
        // 长按与原版一致：首页回首页顶部、刷新强制重载、胶囊出当前页链接菜单、
        // 标签数出「新建 / 清除其它 / 清除全部」、菜单长按直开设置。
        binding.bottomBar.onLongSelect = { id, anchor ->
            when (id) {
                "home" -> (Nav.current() as? HomeFragment)?.scrollToTop()
                "back" -> (Nav.current() as? BrowserFragment)?.goBack()
                "refresh" -> when (val f = Nav.current()) {
                    is BrowserFragment -> f.reload()
                    is HomeFragment -> f.refresh()
                    is TabsFragment -> f.refresh()
                }
                "search" -> MainMenuSheet.showLinkActions(this, anchor)
                "tabs" -> MainMenuSheet.showTabActions(this, anchor)
                "menu" -> Nav.toggle(cn.olfact.app.ui.settings.SettingsFragment())
            }
        }
        refreshBottomBar()
    }

    /** 底栏标题胶囊的统一入口：首页/浏览器聚焦搜索框，其它页回首页并聚焦。 */
    private fun openSearch() {
        // 标签页是覆盖层：先收起，别为了聚焦搜索框把底下的浏览器重建掉。
        if (Nav.current() is TabsFragment) Nav.pop()
        when (val f = Nav.current()) {
            is HomeFragment -> f.focusSearch()
            is BrowserFragment -> f.focusUrlBar()
            else -> {
                // 设置/下载等页面：pop 回首页（保留返回栈，用户按返回还能回来），再聚焦搜索框。
                Nav.openHome()
                (Nav.current() as? HomeFragment)?.focusSearch()
            }
        }
    }

    /** 护眼模式：全屏叠一层 10% 暖色，不拦截触摸。 */
    fun applyEyeCare(enabled: Boolean = AppSettings.get(K.EYE_CARE)) {
        if (!::binding.isInitialized) return
        if (enabled && eyeCareOverlay == null) {
            val overlay = android.view.View(this).apply {
                setBackgroundColor(0x1AFFB74D)
                isClickable = false
                isFocusable = false
            }
            binding.root.addView(
                overlay,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            eyeCareOverlay = overlay
        } else if (!enabled) {
            eyeCareOverlay?.let { binding.root.removeView(it) }
            eyeCareOverlay = null
        }
    }

    /** Android 13+ 通知权限：只主动问一次，拒绝后不再骚扰（可去系统设置里改）。 */
    private fun requestNotifyPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val prefs = getSharedPreferences("olfact_ui", MODE_PRIVATE)
        if (prefs.getBoolean("notify_asked", false)) return
        prefs.edit().putBoolean("notify_asked", true).apply()
        notifyPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    /** 设置页改「底部栏样式」后立即重建底栏，不重启 Activity。 */
    fun applyBottomBarStyle() {
        if (!::binding.isInitialized) return
        setupBottomBar()
        applyChromeForCurrent()
    }

    /** 底栏随当前页面更新：胶囊显示页面标题，第四格显示标签数，第二格是刷新/前进。 */
    fun refreshBottomBar() {
        if (!::binding.isInitialized) return
        val browser = Nav.current() as? BrowserFragment
        // 「前进后退」变体（原版 bottom3）第二格固定是右箭头前进，不随历史变化。
        val refreshIcon = when {
            currentBottomVariant == "back" -> R.drawable.orig_b_forward
            browser?.canGoForward() == true -> R.drawable.orig_b_forward
            else -> R.drawable.orig_b_refresh
        }
        binding.bottomBar.setIcon("refresh", refreshIcon)
        binding.bottomBar.setPillText("search", barTitle())
        binding.bottomBar.setBadge("tabs", BrowserSession.tabs.size)
    }

    private fun barTitle(): String {
        val f = Nav.current()
        return when (f) {
            is HomeFragment -> "主页"
            is BrowserFragment -> f.currentTitle()?.takeIf { it.isNotBlank() } ?: "主页"
            is TabsFragment -> "标签页"
            else -> (f as? ChromeAware)?.barTitle?.takeIf { it.isNotBlank() } ?: "主页"
        }
    }

    private fun observeChrome() {
        supportFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
                applyChromeForCurrent()
            }
        }, false)
    }

    private fun applyChromeForCurrent() {
        if (!::binding.isInitialized) return
        val chrome = Nav.current() as? ChromeAware
        setChrome(chrome?.showsBottomBar ?: false, chrome?.barId)
        refreshBottomBar()
    }

    /**
     * 底栏显隐不带动画：原来的下滑动画在「设置页 → 快速返回首页」时会留下
     * withEndAction，把刚显示的底栏又设成 GONE。底栏在 Fragment 切换时立即到位，
     * 与 ViewStub 的原版行为一致，也避免 inset 与可见状态不同步。
     *
     * 事务切换的中间态 `Nav.current()` 可能是 null，此时**不要**把底栏藏掉：
     * 否则首页/浏览器会闪一下「底栏消失」，再被下一帧恢复。
     */
    fun setChrome(visible: Boolean, barId: String?) {
        if (!visible && barId == null && Nav.current() == null) return
        val bar = binding.bottomBar
        bar.animate().cancel()
        bar.translationY = 0f
        bar.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
        if (visible && barId != null) bar.select(barId, notify = false)
        updateContainerBottomInset()
    }

    // ---------------- 默认浏览器 / 外链分发 ----------------

    private fun handleIntent(intent: Intent?): Boolean {
        intent ?: return false
        // 下载通知：带 EXTRA_ID 直接进详情（PendingIntent 没有 action）
        val downloadId = intent.getStringExtra("cn.olfact.download.EXTRA_ID")
        if (!downloadId.isNullOrBlank()) {
            AppLog.i("intent", "打开下载详情 $downloadId")
            // 连点通知不应该叠出两层一样的详情页。
            val shown = (Nav.current() as? cn.olfact.app.ui.download.DownloadDetailFragment)?.shownTaskId()
            if (shown != downloadId) {
                Nav.push(cn.olfact.app.ui.download.DownloadDetailFragment.newInstance(downloadId))
            }
            return true
        }
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val url = intent.dataString ?: return false
                AppLog.i("intent", "VIEW $url")
                Nav.openBrowser(url)
                return true
            }
            Intent.ACTION_WEB_SEARCH -> {
                val query = intent.getStringExtra(android.app.SearchManager.QUERY) ?: return false
                Nav.openBrowser(SearchEngines.urlFor(query))
                return true
            }
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return false
                return openText(text)
            }
            Intent.ACTION_PROCESS_TEXT -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString() ?: return false
                return openText(text)
            }
            else -> return false
        }
    }

    /** 文本提链：一段话里的多个链接 → 首个打开，其余进后台标签。 */
    private fun openText(text: String): Boolean {
        val links = LinkExtractor.extract(text)
        AppLog.i("intent", "文本提链命中 ${links.size} 条")
        if (links.isEmpty()) {
            Nav.openBrowser(SearchEngines.urlFor(text.trim()))
            return true
        }
        // 其余链接进后台标签，不抢当前页；否则 currentId 会停在最后一个后台标签上。
        links.drop(1).forEach { BrowserSession.open(it.url, selectNew = false) }
        Nav.openBrowser(links.first().url)
        if (links.size > 1) toast("已识别 ${links.size} 个链接，其余 ${links.size - 1} 个在标签页里")
        return true
    }

    fun isDefaultBrowser(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val rm = getSystemService(RoleManager::class.java) ?: return false
        return rm.isRoleHeld(RoleManager.ROLE_BROWSER)
    }

    fun requestDefaultBrowser() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_BROWSER)) {
                if (rm.isRoleHeld(RoleManager.ROLE_BROWSER)) {
                    toast("已经是默认浏览器")
                } else {
                    roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_BROWSER))
                }
                return
            }
        }
        // Android 9 及以下：跳系统「默认应用」设置
        runCatching {
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }.onFailure { toast("请在系统设置里选择默认浏览器") }
    }

    fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
