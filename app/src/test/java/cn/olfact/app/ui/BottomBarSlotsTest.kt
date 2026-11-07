package cn.olfact.app.ui

import cn.olfact.app.R
import cn.olfact.core.uikit.BottomBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 底栏四种变体的槽位表（对齐原版 `view_stub_browser_bottom1..4`）。
 *
 * 这些断言锁的是「顺序 / 图标语义 / 首尾间距」，也就是复制粘贴时代最容易漂移的三样东西。
 */
class BottomBarSlotsTest {

    private fun ids(variant: String) = BottomBarSlots.of(variant).map { it.id }

    @Test
    fun `默认变体是 首页 刷新 胶囊 标签 菜单`() {
        assertEquals(listOf("home", "refresh", "search", "tabs", "menu"), ids("default"))
        // 未知变体（设置里写了脏值）退回默认，不能崩也不能少一格
        assertEquals(ids("default"), ids("whatever"))
    }

    @Test
    fun `纯图标变体槽位与默认一致，只是样式不同`() {
        assertEquals(ids("default"), ids("icon"))
    }

    @Test
    fun `前进后退变体第一格是后退，第二格恒为前进箭头`() {
        val items = BottomBarSlots.of("back")
        assertEquals(listOf("back", "refresh", "search", "tabs", "menu"), items.map { it.id })
        assertEquals(R.drawable.orig_b_back, items[0].icon)
        assertEquals(R.drawable.orig_b_forward, items[1].icon)
        assertEquals("前进或刷新", items[1].description)
        assertTrue(BottomBarSlots.isForwardVariant("back"))
        assertFalse(BottomBarSlots.isForwardVariant("default"))
    }

    @Test
    fun `标签在左变体把标签数放最左`() {
        assertEquals(listOf("tabs", "refresh", "search", "home", "menu"), ids("tabs_left"))
    }

    @Test
    fun `第一个槽位吃左边距 10dp，最后一个吃右边距 10dp`() {
        listOf("default", "icon", "back", "tabs_left").forEach { variant ->
            val items = BottomBarSlots.of(variant)
            assertEquals("$variant 第一格左边距", 10f, items.first().marginStart, 0.01f)
            assertEquals("$variant 最后一格右边距", 10f, items.last().marginEnd, 0.01f)
        }
    }

    @Test
    fun `胶囊与标签数的类型固定`() {
        val items = BottomBarSlots.of("default")
        assertEquals(BottomBar.Kind.PILL, items.first { it.id == "search" }.kind)
        assertEquals(BottomBar.Kind.COUNT, items.first { it.id == "tabs" }.kind)
        assertEquals("主页", items.first { it.id == "search" }.label)
    }
}
