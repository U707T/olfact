package cn.olfact.app.ui.settings

import androidx.test.core.app.ApplicationProvider
import cn.olfact.core.uikit.SettingItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 回归测试：设置行曾经只在 `SWITCH/CHECKBOX` 分支注册点击监听，
 * 导致 `CHEVRON/NONE`（跳转、单选、命令）整行点不动。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingItemClickTest {

    private fun item(): SettingItem = SettingItem(ApplicationProvider.getApplicationContext())

    @Test
    fun `带跳转的设置行可以点击`() {
        val row = item()
        row.mode = SettingItem.Mode.CHEVRON
        var clicks = 0
        row.onClickAction = { clicks++ }
        assertTrue("设了动作就应该可点", row.isClickable)
        row.performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `没有动作的设置行不可点`() {
        val row = item()
        row.mode = SettingItem.Mode.NONE
        assertFalse(row.isClickable)
        row.performClick()
    }

    @Test
    fun `开关行点击整行会翻转并回调`() {
        val row = item()
        row.mode = SettingItem.Mode.SWITCH
        var last: Boolean? = null
        row.onCheckedChange = { last = it }
        row.isCheckedItem = false
        row.performClick()
        assertEquals(true, last)
        row.performClick()
        assertEquals(false, last)
    }

    @Test
    fun `勾选行点击整行会切换`() {
        val row = item()
        row.mode = SettingItem.Mode.CHECKBOX
        var last: Boolean? = null
        row.onCheckedChange = { last = it }
        row.isCheckedItem = false
        row.performClick()
        assertEquals(true, last)
        assertTrue(row.isCheckedItem)
    }
}
