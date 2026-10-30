package cn.olfact.app

import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import cn.olfact.app.data.AppSettings
import cn.olfact.app.data.AppLog
import cn.olfact.app.data.BrowserSession
import cn.olfact.app.data.K
import cn.olfact.app.databinding.ActivityMainBinding
import cn.olfact.app.link.LinkExtractor
import cn.olfact.app.link.SearchEngines
import cn.olfact.app.menu.MainMenuSheet
import cn.olfact.app.nav.ChromeAware
import cn.olfact.app.nav.Nav
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyInsets()

        Nav.attach(this, R.id.container)
        setupBottomBar()
        observeChrome()

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

    override fun onPause() {
        super.onPause()
        BrowserSession.save(this)
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.container.updatePadding(top = bars.top)
            binding.bottomBar.updatePadding(bottom = bars.bottom)
            insets
        }
    }

    private fun setupBottomBar() {
        val style = if (AppSettings.get(K.BOTTOM_STYLE) == "pill") BottomBar.Style.PILL else BottomBar.Style.ICON_ONLY
        binding.bottomBar.configure(
            listOf(
                BottomBar.Item("home", R.drawable.ic_home),
                BottomBar.Item("tabs", R.drawable.ic_tabs),
                BottomBar.Item("main", 0, "主页", pill = true),
                BottomBar.Item("downloads", R.drawable.ic_download),
                BottomBar.Item("menu", R.drawable.ic_grid)
            ),
            style
        )
        binding.bottomBar.onSelect = { id ->
            when (id) {
                "home" -> Nav.root(cn.olfact.app.ui.HomeFragment())
                "tabs" -> Nav.push(cn.olfact.app.ui.TabsFragment())
                "main" -> Nav.openHome()
                "downloads" -> Nav.push(cn.olfact.app.ui.download.DownloadListFragment())
                "menu" -> MainMenuSheet.show(this)
            }
        }
    }

    private fun observeChrome() {
        supportFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
                val chrome = f as? ChromeAware
                setChrome(chrome?.showsBottomBar ?: false, chrome?.barId)
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
            }
            if (barId != null) bar.select(barId, notify = false)
        } else if (bar.visibility == android.view.View.VISIBLE) {
            bar.animate().translationY(bar.height.toFloat()).setDuration(140)
                .withEndAction { bar.visibility = android.view.View.GONE }.start()
        }
    }

    // ---------------- 默认浏览器 / 外链分发 ----------------

    private fun handleIntent(intent: Intent?): Boolean {
        intent ?: return false
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
