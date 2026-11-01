package cn.olfact.app.nav

import android.widget.Toast
import androidx.fragment.app.Fragment
import cn.olfact.app.R
import cn.olfact.app.MainActivity
import cn.olfact.app.ui.HomeFragment
import cn.olfact.app.ui.TabsFragment
import cn.olfact.app.ui.browser.BrowserFragment
import cn.olfact.app.ui.download.DownloadListFragment
import cn.olfact.app.ui.settings.SettingsFragment

/** 页面需要控制底部栏时实现它。 */
interface ChromeAware {
    val showsBottomBar: Boolean get() = false
    val barId: String? get() = null

    /** 底栏标题胶囊显示的文字；null = 由宿主兜底成「主页」。 */
    val barTitle: String? get() = null
}

/**
 * 单 Activity + Fragment 栈。
 *
 * 没有用 Navigation Component：这里只需要「push / pop / 换根」三件事，
 * 引入 nav 会多一个依赖和一套 XML，收益不成正比。
 */
object Nav {

    private var host: MainActivity? = null
    private var containerId: Int = 0

    fun attach(activity: MainActivity, container: Int) {
        host = activity
        containerId = container
    }

    fun detach(activity: MainActivity) {
        if (host === activity) host = null
    }

    private fun tx() = host!!.supportFragmentManager.beginTransaction()

    fun push(fragment: Fragment) {
        val fm = host!!.supportFragmentManager
        // 兜底：容器为空时先放首页，保证任何 push 都有可返回的根页面，
        // 不会出现「返回后容器空白」。
        if (fm.findFragmentById(containerId) == null) {
            fm.beginTransaction().replace(containerId, HomeFragment.newInstance()).commitNow()
        }
        tx().setCustomAnimations(
            R.anim.slide_in_right, R.anim.fade_out,
            R.anim.fade_in, R.anim.slide_out_right
        ).replace(containerId, fragment).addToBackStack(fragment.javaClass.simpleName).commit()
    }

    /**
     * 以「底部弹出」的动效推入覆盖层（原版多窗口是 TranslateFromBottom 的全屏弹层）。
     * 只影响转场动画，返回栈行为与 [push] 一致。
     */
    fun pushSheet(fragment: Fragment) {
        val fm = host!!.supportFragmentManager
        if (fm.findFragmentById(containerId) == null) {
            fm.beginTransaction().replace(containerId, HomeFragment.newInstance()).commitNow()
        }
        tx().setCustomAnimations(
            R.anim.sheet_in, R.anim.fade_out,
            R.anim.fade_in, R.anim.sheet_out
        ).replace(containerId, fragment).addToBackStack(fragment.javaClass.simpleName).commit()
    }

    /**
     * 点一下进入，再点一下退出。
     *
     * 当前页面已经匹配 [matcher] 就 [pop]（回到上一页），否则 [push]。
     * 默认按类名匹配；带参数的页面（如设置子页）传自定义 matcher。
     *
     * 标签页用 [pushSheet]：原版多窗口是从底部升起的覆盖层，不是左右滑动。
     */
    fun toggle(fragment: Fragment, matcher: (Fragment) -> Boolean = { it.javaClass == fragment.javaClass }) {
        if (current()?.let(matcher) == true) {
            pop()
        } else if (fragment is TabsFragment) {
            pushSheet(fragment)
        } else {
            push(fragment)
        }
    }

    /**
     * 换根页面（清空返回栈）。
     *
     * 用 `commitNow` 而不是 `commit`：`replace` 是异步的，中间会有一段「容器里没有
     * Fragment」的窗口，此时 `Nav.current()` 返回 null，底栏会被当成「当前页不需要底栏」
     * 而隐藏、inset 也跟着改——这正是「切标签/返回之后首页 UI 忽隐忽现」的来源。
     */
    fun root(fragment: Fragment) {
        val fm = host!!.supportFragmentManager
        if (fm.backStackEntryCount > 0) {
            fm.popBackStackImmediate(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }
        fm.beginTransaction().replace(containerId, fragment).commitNow()
    }

    /**
     * 返回。栈里没有上一页时回到首页，而不是「点了没反应」。
     *
     * 典型场景：标签页里选标签 / 新建标签走后 [root]，浏览器不在返回栈里，
     * 系统返回键和页面左上角箭头都必须还能回到首页。
     */
    fun pop() {
        val fm = host?.supportFragmentManager ?: return
        // 用 immediate：底栏显隐、inset 都依赖「pop 之后 current() 已经是上一页」，
        // 异步 pop 会让 refreshBottomBar / applyChrome 读到旧页面，出现底栏不更新。
        if (fm.backStackEntryCount > 0) fm.popBackStackImmediate() else openHome()
    }

    /**
     * 如果返回栈里的上一页就是 [clazz]（例如从浏览器进入标签页），pop 回去。
     * @return true 表示已经 pop；调用方不要再 push/root。
     */
    fun popIfPreviousIs(clazz: Class<out Fragment>): Boolean {
        val fm = host?.supportFragmentManager ?: return false
        val previous = fm.backStackEntryCount - 2
        if (previous >= 0 && fm.getBackStackEntryAt(previous).name == clazz.simpleName) {
            pop()
            return true
        }
        return false
    }

    fun current(): Fragment? = host?.supportFragmentManager?.findFragmentById(containerId)

    fun openHome() {
        if (current() is HomeFragment) return
        // 首页是栈底页面：能弹回就弹回，保留同一个 HomeFragment 实例；
        // 只有在栈底不是首页（例如外链冷启动兜底失败）时才重建。
        val fm = host?.supportFragmentManager
        if (fm != null && fm.backStackEntryCount > 0) {
            fm.popBackStackImmediate(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }
        if (current() !is HomeFragment) root(HomeFragment.newInstance())
    }

    /**
     * 标签页覆盖层里选中/新建标签时调用。
     *
     * 原版多窗口是一个覆盖层：选中即收起，底下的页面直接显示新标签，
     * 而不是把 Home 和返回栈一起清掉。这里先 pop 掉覆盖层：
     * - 底下是浏览器 → 直接换绑标签（保留滚动与返回栈）；
     * - 底下是首页 → push 一个浏览器（返回键仍能回到首页）；
     * - 其它（设置/下载等）→ 返回 false，由调用方决定怎么打开。
     *
     * @return true 表示已经完成切换；false 表示底下不是可复用的页面。
     */
    fun switchToTab(tabId: Long): Boolean {
        val fm = host?.supportFragmentManager ?: return false
        if (current() !is TabsFragment) return false
        if (fm.backStackEntryCount > 0) fm.popBackStackImmediate()
        val blank = cn.olfact.app.data.BrowserSession.tabs.firstOrNull { it.id == tabId }?.url.isNullOrBlank()
        return when (val under = current()) {
            is BrowserFragment -> {
                under.showTab(tabId)
                true
            }
            is HomeFragment -> {
                push(BrowserFragment.newInstance(tabId, focusUrl = blank))
                true
            }
            else -> false
        }
    }

    fun goHome() = openHome()

    fun openTabs() = toggle(TabsFragment()) { it is TabsFragment }
    fun openDownloads() = toggle(DownloadListFragment())

    fun openSettings() = toggle(SettingsFragment())

    /**
     * 打开网址。
     *
     * [reuseCurrent] = true 时在当前标签里导航（首页搜索/最近访问），
     * 避免每点一次就新增一个标签；外部链接和书签默认新标签。
     */
    fun openBrowser(url: String?, reuseCurrent: Boolean = false) {
        if (!reuseCurrent && cn.olfact.app.data.BrowserSession.tabs.size >= cn.olfact.app.data.BrowserSession.MAX_TABS) {
            toast("标签已达上限（${cn.olfact.app.data.BrowserSession.MAX_TABS}），最早的标签会被关闭")
        }
        // 已经在浏览器页：只换当前标签的地址，不再叠一个 BrowserFragment
        // （旧实现每点一次首页搜索/最近访问就多一层浏览器，返回键要按很多次）。
        val browser = current() as? BrowserFragment
        if (reuseCurrent && browser != null) {
            cn.olfact.app.data.BrowserSession.openInCurrent(url)
            browser.load(url ?: "about:blank")
            return
        }
        val tab = if (reuseCurrent) {
            cn.olfact.app.data.BrowserSession.openInCurrent(url)
        } else if (url != null) {
            // 外部链接 / 书签：当前恰好是空白页时复用，避免「空白页 + 目标页」两个标签。
            cn.olfact.app.data.BrowserSession.openOrReuseBlank(url)
        } else {
            cn.olfact.app.data.BrowserSession.open(null)
        }
        push(BrowserFragment.newInstance(tab.id, focusUrl = tab.url.isBlank()))
    }

    fun context(): android.content.Context? = host

    fun toast(message: String) {
        val ctx = host ?: return
        Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
    }
}
