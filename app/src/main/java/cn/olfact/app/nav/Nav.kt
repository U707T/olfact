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

    fun current(): Fragment? = host?.supportFragmentManager?.findFragmentById(containerId)

    fun openHome() = root(HomeFragment())

    fun openTabs() = push(TabsFragment())

    fun openDownloads() = push(DownloadListFragment())

    fun openSettings() = push(SettingsFragment())

    fun openBrowser(url: String?) {
        val tab = cn.olfact.app.data.BrowserSession.open(url)
        push(BrowserFragment.newInstance(tab.id))
    }

    fun context(): android.content.Context? = host

    fun toast(message: String) {
        Toast.makeText(host, message, Toast.LENGTH_SHORT).show()
    }
}
