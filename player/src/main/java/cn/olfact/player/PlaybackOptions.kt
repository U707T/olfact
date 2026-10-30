package cn.olfact.player

/** 起播参数：全部可缺省，界面层只在需要时覆盖。 */
data class PlaybackOptions(
    val startPositionMs: Long = 0L,
    val speed: Float = 1f,
    /** 片头秒数：进入后控制条出现"跳片头"，点击 seek 到这里。 */
    val skipIntroMs: Long = 0L,
    /** 片尾秒数：剩余时间小于它时出现"跳片尾"。 */
    val skipOutroMs: Long = 0L,
    val loop: Boolean = false,
    /** 离开页面自动进 PiP（系统支持时）。 */
    val autoPipOnLeave: Boolean = true,
    /** 记住本次播放速度（默认记住）。 */
    val rememberSpeed: Boolean = true,
    /** 记住播放进度（默认记住）。 */
    val rememberPosition: Boolean = true,
) {
    companion object {
        val DEFAULT = PlaybackOptions()
    }
}
