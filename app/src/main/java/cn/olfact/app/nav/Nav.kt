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

    private fun tx() = host!!.supportFragmentManager.beginTransaction()

    fun push(fragment: Fragment) {
        tx().setCustomAnimations(
            R.anim.slide_in_right, R.anim.fade_out,
            R.anim.fade_in, R.anim.slide_out_right
        ).replace(containerId, fragment).addToBackStack(fragment.javaClass.simpleName).commit()
    }

    /**
     * 点一下进入，再点一下退出。
     *
     * 当前页面已经匹配 [matcher] 就 [pop]（回到上一页），否则 [push]。
     * 默认按类名匹配；带参数的页面（如设置子页）传自定义 matcher。
     */
    fun toggle(fragment: Fragment, matcher: (Fragment) -> Boolean = { it.javaClass == fragment.javaClass }) {
        if (current()?.let(matcher) == true) {
            pop()
        } else {
            push(fragment)
        }
    }

    fun root(fragment: Fragment) {
        val fm = host!!.supportFragmentManager
        if (fm.backStackEntryCount > 0) {
            fm.popBackStackImmediate(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }
        tx().replace(containerId, fragment).commit()
    }

    fun pop() {
        val fm = host?.supportFragmentManager ?: return
        if (fm.backStackEntryCount > 0) fm.popBackStack()
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
        root(HomeFragment())
    }

    fun openTabs() = toggle(TabsFragment())

    fun openDownloads() = toggle(DownloadListFragment())

    fun openSettings() = toggle(SettingsFragment())

    fun openBrowser(url: String?) {
        if (cn.olfact.app.data.BrowserSession.tabs.size >= cn.olfact.app.data.BrowserSession.MAX_TABS) {
            toast("标签已达上限（${cn.olfact.app.data.BrowserSession.MAX_TABS}），最早的标签会被关闭")
        }
        val tab = cn.olfact.app.data.BrowserSession.open(url)
        push(BrowserFragment.newInstance(tab.id))
    }

    fun context(): android.content.Context? = host

    fun toast(message: String) {
        Toast.makeText(host, message, Toast.LENGTH_SHORT).show()
    }
}
