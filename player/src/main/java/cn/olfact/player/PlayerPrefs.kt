package cn.olfact.player

import android.content.Context

/**
 * 播放器自己的轻量偏好（速度 / 断点）。只存本模块数据，不碰 core 的 SettingsRepository，
 * 避免 B3 与 B1 的 DataStore 相互阻塞。
 */
class PlayerPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var playbackSpeed: Float
        get() = prefs.getFloat(KEY_SPEED, 1f).coerceIn(MIN_SPEED, MAX_SPEED)
        set(value) = prefs.edit().putFloat(KEY_SPEED, value.coerceIn(MIN_SPEED, MAX_SPEED)).apply()

    fun positionOf(resumeKey: String): Long = prefs.getLong(KEY_POSITION + resumeKey.hashCode().toUInt(), 0L)

    fun savePosition(resumeKey: String, positionMs: Long) {
        if (positionMs <= 0L) return
        prefs.edit().putLong(KEY_POSITION + resumeKey.hashCode().toUInt(), positionMs).apply()
    }

    fun clearPosition(resumeKey: String) {
        prefs.edit().remove(KEY_POSITION + resumeKey.hashCode().toUInt()).apply()
    }

    fun clearAllPositions() {
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith(KEY_POSITION) }.forEach(edit::remove)
        edit.apply()
    }

    companion object {
        private const val PREFS_NAME = "olfact_player"
        private const val KEY_SPEED = "speed"
        private const val KEY_POSITION = "pos_"

        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3.0f

        val SPEED_STEPS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)

        fun clampSpeed(speed: Float): Float = speed.coerceIn(MIN_SPEED, MAX_SPEED)

        /** 倍速向下取最近一档（长按倍速时保证不会超过 3.0x）。 */
        fun speedLabel(speed: Float): String {
            val v = clampSpeed(speed)
            return if (v == v.toInt().toFloat()) "${v.toInt()}.0x" else "${v}x"
        }
    }
}
