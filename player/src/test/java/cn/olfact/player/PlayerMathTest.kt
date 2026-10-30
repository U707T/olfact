package cn.olfact.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerMathTest {

    @Test
    fun `时间格式化`() {
        assertEquals("00:00", PlayerMath.formatDuration(0))
        assertEquals("00:07", PlayerMath.formatDuration(7_400))
        assertEquals("01:05", PlayerMath.formatDuration(65_000))
        assertEquals("1:02:03", PlayerMath.formatDuration(3_723_000))
    }

    @Test
    fun `seek 夹在 0 和时长之间`() {
        assertEquals(0L, PlayerMath.seekTarget(2_000, 600_000, -10_000))
        assertEquals(600_000L, PlayerMath.seekTarget(595_000, 600_000, 10_000))
        assertEquals(15_000L, PlayerMath.seekTarget(5_000, 600_000, 10_000))
    }

    @Test
    fun `时长未知时不设上限`() {
        assertEquals(120_000L, PlayerMath.seekTarget(110_000, 0, 10_000))
    }

    @Test
    fun `跳片头只在片头区间显示`() {
        assertTrue(PlayerMath.shouldShowSkipIntro(30_000, 90_000))
        assertFalse(PlayerMath.shouldShowSkipIntro(95_000, 90_000))
        assertFalse(PlayerMath.shouldShowSkipIntro(10_000, 0))
    }

    @Test
    fun `跳片尾按剩余时间判断`() {
        assertTrue(PlayerMath.shouldShowSkipOutro(560_000, 600_000, 60_000))
        assertFalse(PlayerMath.shouldShowSkipOutro(400_000, 600_000, 60_000))
        assertFalse(PlayerMath.shouldShowSkipOutro(599_000, 600_000, 0))
        assertFalse(PlayerMath.shouldShowSkipOutro(0, 0, 60_000))
    }

    @Test
    fun `进度百分比边界`() {
        assertEquals(50, PlayerMath.progressPercent(500, 1000))
        assertEquals(0, PlayerMath.progressPercent(-5, 1000))
        assertEquals(100, PlayerMath.progressPercent(2000, 1000))
        assertEquals(0, PlayerMath.progressPercent(100, 0))
    }

    @Test
    fun `倍速标签与夹取`() {
        assertEquals(0.5f, PlayerPrefs.clampSpeed(0.1f))
        assertEquals(3.0f, PlayerPrefs.clampSpeed(9f))
        assertEquals("1.0x", PlayerPrefs.speedLabel(1f))
        assertEquals("1.5x", PlayerPrefs.speedLabel(1.5f))
        assertEquals("2.0x", PlayerPrefs.speedLabel(2f))
    }

    @Test
    fun `异常宽高返回 null`() {
        assertEquals(1920 to 1080, PlayerMath.safeAspect(1920, 1080))
        assertEquals(null, PlayerMath.safeAspect(0, 1080))
        assertEquals(null, PlayerMath.safeAspect(1920, -1))
    }
}
