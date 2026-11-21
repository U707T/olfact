package cn.olfact.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackSessionTest {
    @Test fun `background return restores latest progress instead of explicit initial position`() {
        val session = PlaybackSession(10_000, 1f)
        session.capture(125_000, 1.5f, true)
        assertEquals(125_000L, session.snapshot.positionMs)
        assertEquals(1.5f, session.snapshot.speed)
        assertTrue(session.snapshot.playWhenReady)
    }

    @Test fun `private or nonremembered sessions keep progress in memory only`() {
        val session = PlaybackSession(0, 1f)
        session.capture(90_000, 2f, false)
        assertEquals(90_000L, session.snapshot.positionMs)
        assertFalse(session.snapshot.playWhenReady)
        assertEquals(2f, session.snapshot.speed)
        assertEquals(0L, PlaybackSession(0, 1f).snapshot.positionMs)
    }

    @Test fun `buffering keeps play intent when isPlaying would be false`() {
        val session = PlaybackSession(0, 1f)
        session.capture(0, 1f, playWhenReady = true)
        assertTrue(session.snapshot.playWhenReady)
    }

    @Test fun `nonremembered speed changes survive player recreation`() {
        val session = PlaybackSession(0, 1f)
        session.setSpeed(1.75f)
        assertEquals(1.75f, session.snapshot.speed)
    }

    @Test fun `invalid speeds fall back to a valid playback speed`() {
        assertEquals(1f, PlayerPrefs.clampSpeed(Float.NaN))
        assertEquals(1f, PlayerPrefs.clampSpeed(Float.POSITIVE_INFINITY))
    }
}
