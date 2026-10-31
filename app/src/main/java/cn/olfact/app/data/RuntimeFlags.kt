package cn.olfact.app.data

/**
 * 只影响当前进程的一次性标记（不落盘）。
 *
 * 用途：设置页改了「夜间网页 / 无图 / 字号 / UA / JS」后，已经打开的 WebView
 * 不会自己重新应用配置；标记一下，浏览器下次回到前台时自动 reload。
 */
object RuntimeFlags {

    /** true = 下一次进入浏览器页时刷新当前页面。 */
    @Volatile
    var reloadPagesOnResume: Boolean = false
}
