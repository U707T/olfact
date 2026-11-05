package cn.olfact.download.util

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedMeterTest {

    @Test
    fun `首次采样没有速度`() {
        val meter = SpeedMeter()
        assertEquals(0L, meter.sample("a", 0, 1_000))
        assertEquals(0L, meter.speedOf("a"))
    }

    @Test
    fun `按两次采样的增量算速度`() {
        val meter = SpeedMeter()
        meter.sample("a", 0, 0)
        // 1 秒下了 1000 字节 → 1000 B/s
        assertEquals(1_000L, meter.sample("a", 1_000, 1_000))
        assertEquals(1_000L, meter.speedOf("a"))
    }

    @Test
    fun `间隔太短沿用上次结果`() {
        val meter = SpeedMeter()
        meter.sample("a", 0, 0)
        meter.sample("a", 1_000, 1_000)
        // 200ms 后又来一次，不重新计算
        assertEquals(1_000L, meter.sample("a", 5_000, 1_200))
    }

    @Test
    fun `字节回退不会产生负速度`() {
        val meter = SpeedMeter()
        meter.sample("a", 10_000, 0)
        val speed = meter.sample("a", 2_000, 1_000)
        assertEquals(0L, speed)
    }

    @Test
    fun `速度做指数平滑`() {
        val meter = SpeedMeter()
        meter.sample("a", 0, 0)
        meter.sample("a", 1_000, 1_000) // 1000
        // 下一秒下了 4000 → 瞬时 4000；平滑 = (1000*2 + 4000)/3 = 2000
        assertEquals(2_000L, meter.sample("a", 5_000, 2_000))
    }

    @Test
    fun `reset 后重新从零开始`() {
        val meter = SpeedMeter()
        meter.sample("a", 0, 0)
        meter.sample("a", 1_000, 1_000)
        meter.reset("a")
        assertEquals(0L, meter.speedOf("a"))
        assertEquals(0L, meter.sample("a", 2_000, 2_000))
    }

    @Test
    fun `两个任务互不影响`() {
        val meter = SpeedMeter()
        meter.sample("a", 0, 0)
        meter.sample("b", 0, 0)
        meter.sample("a", 1_000, 1_000)
        meter.sample("b", 500, 1_000)
        assertEquals(1_000L, meter.speedOf("a"))
        assertEquals(500L, meter.speedOf("b"))
    }
}
