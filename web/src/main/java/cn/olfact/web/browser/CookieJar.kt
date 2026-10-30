package cn.olfact.web.browser

import android.webkit.CookieManager

/**
 * 普通 / 无痕 Cookie 隔离。
 *
 * Android 的 [CookieManager] 是进程级单例，没有“两套 profile”的概念，
 * 所以这里用「快照 → 清空 → 退出恢复」的方式实现无痕不落盘：
 * 进入无痕时把普通模式访问过的域名的 Cookie 存到内存并清空全局库，
 * 退出无痕（最后一个无痕标签关闭）时再写回。
 *
 * 已知边界：进程被系统杀死时，内存中的快照会丢失，普通模式的登录态需要重新登录。
 */
class CookieJar(private val onLog: (String) -> Unit = {}) {

    private val lock = Any()
    private val visitedHosts = LinkedHashSet<String>()
    private val persistentSnapshot = HashMap<String, String>()

    @Volatile
    private var incognitoTabs = 0

    private val manager: CookieManager get() = CookieManager.getInstance()

    fun recordHost(host: String) {
        val normalized = SiteConfig.normalizeHost(host)
        if (normalized.isEmpty()) return
        synchronized(lock) { visitedHosts += normalized }
    }

    fun isIncognito(): Boolean = incognitoTabs > 0

    fun enterIncognito() {
        synchronized(lock) {
            if (incognitoTabs == 0) {
                snapshotPersistent()
                runCatching { manager.removeAllCookies(null) }
                onLog("进入无痕：已暂存 ${persistentSnapshot.size} 个域名的 Cookie 并清空")
            }
            incognitoTabs++
        }
    }

    fun exitIncognito() {
        synchronized(lock) {
            if (incognitoTabs == 0) return
            incognitoTabs--
            if (incognitoTabs > 0) return
            runCatching {
                manager.removeAllCookies {
                    synchronized(lock) { restorePersistent() }
                }
            }
        }
    }

    /** 无痕会话内主动清 Cookie（“关闭全部无痕标签”时用）。 */
    fun clearIncognito() {
        runCatching { manager.removeAllCookies(null) }
    }

    fun clearAll() {
        runCatching {
            manager.removeAllCookies(null)
            manager.flush()
        }
        synchronized(lock) {
            persistentSnapshot.clear()
            visitedHosts.clear()
        }
    }

    private fun snapshotPersistent() {
        persistentSnapshot.clear()
        manager.flush()
        visitedHosts.forEach { host ->
            val cookie = runCatching { manager.getCookie(urlFor(host)) }.getOrNull()
            if (!cookie.isNullOrBlank()) persistentSnapshot[host] = cookie
        }
    }

    private fun restorePersistent() {
        manager.flush()
        persistentSnapshot.forEach { (host, cookie) ->
            runCatching { manager.setCookie(urlFor(host), cookie) }
        }
        manager.flush()
        onLog("退出无痕：已恢复 ${persistentSnapshot.size} 个域名的 Cookie")
        persistentSnapshot.clear()
    }

    private fun urlFor(host: String): String = "https://$host/"
}
