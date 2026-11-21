package cn.olfact.player

/** In-memory state survives player release, independently of opt-in persistent history. */
internal class PlaybackSession(startPositionMs: Long, speed: Float) {
    data class Snapshot(val positionMs: Long, val speed: Float, val playWhenReady: Boolean)

    var snapshot = Snapshot(startPositionMs.coerceAtLeast(0), PlayerPrefs.clampSpeed(speed), true)
        private set

    fun capture(positionMs: Long, speed: Float, playWhenReady: Boolean) {
        snapshot = Snapshot(positionMs.coerceAtLeast(0), PlayerPrefs.clampSpeed(speed), playWhenReady)
    }

    fun setSpeed(speed: Float) {
        snapshot = snapshot.copy(speed = PlayerPrefs.clampSpeed(speed))
    }
}
