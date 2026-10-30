package cn.olfact.web.doh

/**
 * 会话粘性（TECH-PLAN §4.5-7）。
 *
 * 某标签页主域命中 DoH 规则后，该页发起的所有 host 都走 DoH，
 * 这样第三方子资源（CDN、统计、字体）也一起加密，拦截率从 ~60% 提到 ~90%。
 */
class SessionSticky(
    private val ttlMs: Long = 10 * 60_000L,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    private val lock = Any()
    private val stickyHosts = HashMap<String, Long>()
    private val stickyTabs = HashMap<String, MutableSet<String>>()

    /** 主文档加载时调用。useDoh 为该主域是否已经决定走 DoH。 */
    fun onMainFrame(tabId: String, host: String, useDoh: Boolean) {
        val h = host.normalizeHost()
        if (h.isEmpty() || !useDoh) return
        synchronized(lock) {
            stickyHosts[h] = clock() + ttlMs
            stickyTabs.getOrPut(tabId) { HashSet() }.add(h)
        }
    }

    /** 页内任意子资源请求时调用；只有该标签页已经进入粘性模式才记录。 */
    fun touch(tabId: String, host: String) {
        val h = host.normalizeHost()
        if (h.isEmpty()) return
        synchronized(lock) {
            val hosts = stickyTabs[tabId] ?: return
            if (hosts.isEmpty()) return
            stickyHosts[h] = clock() + ttlMs
            hosts.add(h)
        }
    }

    fun isStickyHost(host: String): Boolean {
        val h = host.normalizeHost()
        if (h.isEmpty()) return false
        synchronized(lock) {
            prune()
            if (stickyHosts.containsKey(h)) return true
            var index = h.indexOf('.')
            while (index >= 0) {
                val parent = h.substring(index + 1)
                if (stickyHosts.containsKey(parent)) return true
                index = h.indexOf('.', index + 1)
            }
            return false
        }
    }

    fun closeTab(tabId: String) {
        synchronized(lock) { stickyTabs.remove(tabId) }
    }

    fun reset() {
        synchronized(lock) {
            stickyHosts.clear()
            stickyTabs.clear()
        }
    }

    fun hostCount(): Int = synchronized(lock) {
        prune()
        stickyHosts.size
    }

    private fun prune() {
        val now = clock()
        val iterator = stickyHosts.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value <= now) iterator.remove()
        }
    }
}
