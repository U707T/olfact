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
import cn.olfact.app.data.HistoryStore
import cn.olfact.app.data.K
import cn.olfact.app.databinding.ActivityMainBinding
import cn.olfact.app.link.LinkExtractor
import cn.olfact.app.link.SearchEngines
import cn.olfact.app.menu.MainMenuSheet
import cn.olfact.app.nav.ChromeAware
import cn.olfact.app.nav.Nav
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
        setupBottomBar()
        observeChrome()
        applyEyeCare()
        requestNotifyPermissionOnce()

        if (savedInstanceState == null) {
            BrowserSession.restoreIfNeeded(this)
            if (!handleIntent(intent)) Nav.openHome()
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
        // 设置页改「底部栏样式」后回到主界面立即生效，不必重启。
        val style = if (AppSettings.get(K.BOTTOM_STYLE) == "pill") BottomBar.Style.PILL else BottomBar.Style.ICON_ONLY
        if (currentBottomStyle != null && currentBottomStyle != style) setupBottomBar()
    }

    override fun onPause() {
        super.onPause()
        BrowserSession.save(this)
        // 历史/书签是 debounce 落盘；退到后台时补一次，降低被杀丢数据的概率。
        HistoryStore.flush(this)
        BookmarkStore.flush(this)
    }

    override fun onDestroy() {
        // 「退出时清除数据」：只在本 Activity 真被销毁（返回键/划掉）时清，
        // 配置变更（旋转、切深色）不能误清。
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
        val style = if (AppSettings.get(K.BOTTOM_STYLE) == "pill") BottomBar.Style.PILL else BottomBar.Style.ICON_ONLY
        currentBottomStyle = style
        // 五槽对齐原版 view_stub_browser_bottom：首页 / 刷新 / 标题胶囊 / 标签数 / 菜单
        binding.bottomBar.configure(
            listOf(
                BottomBar.Item("home", R.drawable.orig_b_home),
                BottomBar.Item("action", R.drawable.orig_b_tabs),
                BottomBar.Item("main", icon = R.drawable.orig_b_home, label = "主页", kind = BottomBar.Kind.PILL),
                BottomBar.Item("tabs", R.drawable.orig_b_tabs, kind = BottomBar.Kind.COUNT),
                BottomBar.Item("menu", R.drawable.orig_b_menu)
            ),
            style
        )
        binding.bottomBar.onSelect = { id ->
            when (id) {
                "home" -> Nav.root(cn.olfact.app.ui.HomeFragment())
                // 浏览器里是「刷新」，其它页面是「标签页」
                "action" -> {
                    val browser = Nav.current() as? cn.olfact.app.ui.browser.BrowserFragment
                    if (browser != null) browser.reload() else Nav.push(cn.olfact.app.ui.TabsFragment())
                }
                "main", "tabs" -> Nav.push(cn.olfact.app.ui.TabsFragment())
                "menu" -> MainMenuSheet.show(this)
            }
        }
        refreshBottomBar()
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
        refreshBottomBar()
    }

    /** 底栏随当前页面更新：胶囊显示页面标题，第四格显示标签数，第二格切换刷新/标签。 */
    fun refreshBottomBar() {
        if (!::binding.isInitialized) return
        val browser = Nav.current() as? cn.olfact.app.ui.browser.BrowserFragment
        binding.bottomBar.setIcon("action", if (browser != null) R.drawable.orig_b_refresh else R.drawable.orig_b_tabs)
        binding.bottomBar.setPillText("main", browser?.currentTitle() ?: "主页")
        binding.bottomBar.setBadge("tabs", BrowserSession.tabs.size.coerceAtLeast(1))
    }

    private fun observeChrome() {
        supportFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
                val chrome = f as? ChromeAware
                setChrome(chrome?.showsBottomBar ?: false, chrome?.barId)
                refreshBottomBar()
            }
        }, false)
    }

    fun setChrome(visible: Boolean, barId: String?) {
        val bar = binding.bottomBar
        bar.animate().cancel()
        if (visible) {
            if (bar.visibility != android.view.View.VISIBLE) {
                bar.visibility = android.view.View.VISIBLE
                bar.translationY = bar.height.toFloat()
                bar.animate().translationY(0f).setDuration(180).start()
                updateContainerBottomInset()
            }
            if (barId != null) bar.select(barId, notify = false)
        } else if (bar.visibility == android.view.View.VISIBLE) {
            bar.animate().translationY(bar.height.toFloat()).setDuration(140)
                .withEndAction {
                    bar.visibility = android.view.View.GONE
                    updateContainerBottomInset()
                }.start()
        }
    }

    // ---------------- 默认浏览器 / 外链分发 ----------------

    private fun handleIntent(intent: Intent?): Boolean {
        intent ?: return false
        // 下载通知：带 EXTRA_ID 直接进详情（PendingIntent 没有 action）
        val downloadId = intent.getStringExtra("cn.olfact.download.EXTRA_ID")
        if (!downloadId.isNullOrBlank()) {
            AppLog.i("intent", "打开下载详情 $downloadId")
            Nav.push(cn.olfact.app.ui.download.DownloadDetailFragment.newInstance(downloadId))
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
        links.drop(1).forEach { BrowserSession.open(it.url) }
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
