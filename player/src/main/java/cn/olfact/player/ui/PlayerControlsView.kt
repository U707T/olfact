package cn.olfact.player.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Player
import cn.olfact.player.PlayerMath
import cn.olfact.player.PlayerPrefs
import cn.olfact.player.R

/**
 * 自绘控制条：播放/暂停、进度、倍速、跳片头片尾、锁屏、PiP、横竖屏。
 * 不用 media3 的 PlayerControlView —— 它的按钮顺序和样式跟原版不一致。
 */
class PlayerControlsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    interface Listener {
        fun onPlayPauseClick()
        fun onSeekTo(positionMs: Long, fromUser: Boolean)
        fun onBackClick()
        fun onPipClick()
        fun onSpeedClick(anchor: View)
        fun onFullscreenClick()
        fun onSkipIntroClick()
        fun onSkipOutroClick()
        fun onLockChanged(locked: Boolean)
    }

    var listener: Listener? = null

    init {
        // 必须先 inflate，后面的 by-id 属性初始化才能拿到 View。
        LayoutInflater.from(context).inflate(R.layout.view_player_controls, this, true)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val topBar: View = findViewById(R.id.top_bar)
    private val bottomBar: View = findViewById(R.id.bottom_bar)
    private val scrimTop: View = findViewById(R.id.scrim_top)
    private val scrimBottom: View = findViewById(R.id.scrim_bottom)
    private val btnBack: ImageButton = findViewById(R.id.btn_back)
    private val btnPip: ImageButton = findViewById(R.id.btn_pip)
    private val btnPlayPause: ImageButton = findViewById(R.id.btn_play_pause)
    private val btnSpeed: TextView = findViewById(R.id.btn_speed)
    private val btnLock: ImageButton = findViewById(R.id.btn_lock)
    private val btnUnlock: ImageButton = findViewById(R.id.btn_unlock)
    private val btnFullscreen: ImageButton = findViewById(R.id.btn_fullscreen)
    private val btnSkipIntro: ImageButton = findViewById(R.id.btn_skip_intro)
    private val btnSkipOutro: ImageButton = findViewById(R.id.btn_skip_outro)
    private val seekBar: SeekBar = findViewById(R.id.seek_bar)
    private val tvPosition: TextView = findViewById(R.id.tv_position)
    private val tvDuration: TextView = findViewById(R.id.tv_duration)
    private val tvTitle: TextView = findViewById(R.id.tv_title)
    private val tvFeedback: TextView = findViewById(R.id.tv_feedback)

    private var player: Player? = null
    private var locked = false
    private var showing = true
    private var userSeeking = false
    private var skipIntroMs = 0L
    private var skipOutroMs = 0L
    private var autoHideEnabled = true

    private val hideRunnable = Runnable { setControlsVisible(false) }

    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            handler.removeCallbacks(this)
            if (showing && player != null) handler.postDelayed(this, 500L)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayPauseIcon()
            scheduleAutoHide()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            updatePlayPauseIcon()
            updateProgress()
            if (playbackState == Player.STATE_ENDED && !locked) setControlsVisible(true)
        }

        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
            setSpeedLabel(playbackParameters.speed)
        }
    }

    init {
        btnBack.setOnClickListener { listener?.onBackClick() }
        btnPip.setOnClickListener { listener?.onPipClick() }
        btnFullscreen.setOnClickListener { listener?.onFullscreenClick() }
        btnPlayPause.setOnClickListener {
            listener?.onPlayPauseClick()
            showTemporarily()
        }
        btnSpeed.setOnClickListener { listener?.onSpeedClick(btnSpeed) }
        btnLock.setOnClickListener { setLocked(true) }
        btnUnlock.setOnClickListener { setLocked(false) }
        btnSkipIntro.setOnClickListener { listener?.onSkipIntroClick() }
        btnSkipOutro.setOnClickListener { listener?.onSkipOutroClick() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val duration = durationOrZero()
                if (duration > 0) tvPosition.text = PlayerMath.formatDuration(duration * progress / SEEKBAR_MAX)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                userSeeking = true
                handler.removeCallbacks(hideRunnable)
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                userSeeking = false
                val duration = durationOrZero()
                if (duration > 0) {
                    listener?.onSeekTo(duration * bar.progress / SEEKBAR_MAX, true)
                }
                scheduleAutoHide()
            }
        })
    }

    fun attach(player: Player, title: String? = null) {
        this.player?.removeListener(playerListener)
        this.player = player
        player.addListener(playerListener)
        tvTitle.text = title.orEmpty()
        if (title.isNullOrBlank()) tvTitle.visibility = View.GONE else tvTitle.visibility = View.VISIBLE
        updatePlayPauseIcon()
        updateProgress()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        setControlsVisible(true)
        setSpeedLabel(player.playbackParameters.speed)
    }

    fun detach() {
        handler.removeCallbacksAndMessages(null)
        player?.removeListener(playerListener)
        player = null
    }

    override fun onDetachedFromWindow() {
        detach()
        super.onDetachedFromWindow()
    }

    fun setSkipPoints(introMs: Long, outroMs: Long) {
        skipIntroMs = introMs.coerceAtLeast(0L)
        skipOutroMs = outroMs.coerceAtLeast(0L)
        updateSkipButtons()
    }

    fun setSpeedLabel(speed: Float) {
        btnSpeed.text = PlayerPrefs.speedLabel(speed)
    }

    fun setAutoHideEnabled(enabled: Boolean) {
        autoHideEnabled = enabled
    }

    val isLocked: Boolean
        get() = locked

    fun setLocked(value: Boolean) {
        if (locked == value) return
        locked = value
        topBar.visibility = if (value) View.GONE else View.VISIBLE
        bottomBar.visibility = if (value) View.GONE else View.VISIBLE
        scrimTop.visibility = if (value) View.GONE else View.VISIBLE
        scrimBottom.visibility = if (value) View.GONE else View.VISIBLE
        btnUnlock.visibility = if (value) View.VISIBLE else View.GONE
        btnLock.setImageResource(if (value) R.drawable.ic_player_unlock else R.drawable.ic_player_lock)
        listener?.onLockChanged(value)
        if (value) {
            flashFeedback(context.getString(R.string.player_locked_hint))
        } else {
            flashFeedback(context.getString(R.string.player_unlocked_hint))
            setControlsVisible(true)
        }
    }

    fun toggle() {
        if (locked) {
            showTemporarily()
            return
        }
        setControlsVisible(!showing)
    }

    fun showTemporarily() {
        setControlsVisible(true)
    }

    fun setControlsVisible(visible: Boolean) {
        if (locked) {
            btnUnlock.visibility = View.VISIBLE
            return
        }
        showing = visible
        val visibility = if (visible) View.VISIBLE else View.GONE
        for (view in listOf(topBar, bottomBar, scrimTop, scrimBottom)) {
            view.visibility = visibility
            view.alpha = if (visible) 1f else 0f
        }
        if (visible) {
            scheduleAutoHide()
            handler.removeCallbacks(ticker)
            handler.post(ticker)
        } else {
            handler.removeCallbacks(hideRunnable)
            handler.removeCallbacks(ticker)
        }
    }

    fun flashFeedback(text: String) {
        tvFeedback.animate().cancel()
        tvFeedback.text = text
        tvFeedback.alpha = 1f
        tvFeedback.visibility = View.VISIBLE
        handler.removeCallbacks(feedbackRunnable)
        handler.postDelayed(feedbackRunnable, 800L)
    }

    private val feedbackRunnable = Runnable {
        tvFeedback.animate().alpha(0f).setDuration(250L)
            .withEndAction { tvFeedback.visibility = View.GONE }
            .start()
    }

    private fun scheduleAutoHide() {
        handler.removeCallbacks(hideRunnable)
        val p = player ?: return
        if (autoHideEnabled && !locked && p.isPlaying) {
            handler.postDelayed(hideRunnable, AUTO_HIDE_MS)
        }
    }

    private fun updatePlayPauseIcon() {
        val playing = player?.isPlaying == true
        btnPlayPause.setImageResource(if (playing) R.drawable.ic_player_pause else R.drawable.ic_player_play)
    }

    private fun durationOrZero(): Long {
        val d = player?.duration ?: 0L
        return if (d == C.TIME_UNSET || d < 0) 0L else d
    }

    private fun updateProgress() {
        val p = player ?: return
        if (userSeeking) return
        val duration = durationOrZero()
        val position = p.currentPosition.coerceAtLeast(0L)
        tvPosition.text = PlayerMath.formatDuration(position)
        tvDuration.text = if (duration > 0) PlayerMath.formatDuration(duration) else "--:--"
        seekBar.isEnabled = duration > 0
        if (duration > 0) {
            seekBar.progress = ((position * SEEKBAR_MAX) / duration).toInt().coerceIn(0, SEEKBAR_MAX)
        }
        updateSkipButtons()
    }

    private fun updateSkipButtons() {
        val p = player ?: return
        val duration = durationOrZero()
        val position = p.currentPosition
        btnSkipIntro.visibility =
            if (PlayerMath.shouldShowSkipIntro(position, skipIntroMs)) View.VISIBLE else View.GONE
        btnSkipOutro.visibility =
            if (PlayerMath.shouldShowSkipOutro(position, duration, skipOutroMs)) View.VISIBLE else View.GONE
    }

    companion object {
        private const val SEEKBAR_MAX = 1000
        private const val AUTO_HIDE_MS = 3500L
    }
}
