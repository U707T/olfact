package cn.olfact.app.ui

import cn.olfact.app.R
import cn.olfact.core.uikit.BottomBar

/**
 * 底栏四种变体的槽位表，对齐原版 `view_stub_browser_bottom1..4`。
 *
 * 原版是 4 份 layout；这里用「一份槽位定义 + 每个变体的 id 顺序」表达，
 * 避免三段 `listOf(BottomBar.Item(...))` 复制粘贴漂移——之前 `back` 变体
 * 把前进图标装进 `refresh` 槽，再靠 `refreshBottomBar()` 里的
 * `currentBottomVariant == "back"` 特判兜回来，就是复制粘贴留下的暗扣。
 */
internal object BottomBarSlots {

    /** 原版容器左右 padding 10dp：第一个槽位的 marginStart 与最后一个的 marginEnd 都要吃它。 */
    private const val EDGE_START = 10f
    private const val EDGE_END = 10f

    private val HOME = BottomBar.Item(
        id = "home", icon = R.drawable.orig_b_home, description = "首页", marginStart = 18f
    )
    private val REFRESH = BottomBar.Item(
        id = "refresh", icon = R.drawable.orig_b_refresh, description = "刷新或前进",
        marginStart = 15f, marginEnd = 18f
    )

    /** `back` 变体的第二格固定是「前进」（原版 bottom3）：同一个 id，换图标与播报。 */
    private val FORWARD = BottomBar.Item(
        id = "refresh", icon = R.drawable.orig_b_forward, description = "前进或刷新",
        marginStart = 15f, marginEnd = 18f
    )
    private val BACK = BottomBar.Item(
        id = "back", icon = R.drawable.orig_b_back, description = "后退", marginStart = 10f
    )
    private val SEARCH = BottomBar.Item(
        id = "search", label = "主页", kind = BottomBar.Kind.PILL, description = "地址与搜索",
        marginStart = 5f, marginEnd = 5f
    )
    private val TABS = BottomBar.Item(
        id = "tabs", kind = BottomBar.Kind.COUNT, description = "标签页", marginStart = 18f
    )
    private val MENU = BottomBar.Item(
        id = "menu", icon = R.drawable.orig_b_menu, description = "菜单",
        marginStart = 15f, marginEnd = 10f
    )

    /** 变体 id → 槽位顺序。`icon` 变体只换样式，槽位与默认一致。 */
    private val ORDERS: Map<String, List<BottomBar.Item>> = mapOf(
        "back" to listOf(BACK, FORWARD, SEARCH, TABS, MENU),
        "tabs_left" to listOf(TABS, REFRESH, SEARCH, HOME, MENU),
        "default" to listOf(HOME, REFRESH, SEARCH, TABS, MENU),
        "icon" to listOf(HOME, REFRESH, SEARCH, TABS, MENU),
    )

    fun of(variant: String): List<BottomBar.Item> {
        val items = ORDERS[variant] ?: ORDERS.getValue("default")
        return items.mapIndexed { index, item ->
            when {
                index == 0 -> item.copy(marginStart = EDGE_START)
                index == items.lastIndex -> item.copy(marginEnd = EDGE_END)
                else -> item
            }
        }
    }

    /** 「前进后退」变体（原版 bottom3）：第二格恒为前进箭头，不随历史变化。 */
    fun isForwardVariant(variant: String?): Boolean = variant == "back"
}
