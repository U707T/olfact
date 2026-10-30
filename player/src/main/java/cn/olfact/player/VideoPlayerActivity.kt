package cn.olfact.player

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.WebSettings
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import cn.olfact.player.external.FallbackPlayer
import cn.olfact.player.gesture.PlayerGestureController
import cn.olfact.player.ui.PlayerControlsView

/**
 * 内置播放页。刻意不继承 AppCompatActivity：player 模块不依赖 appcompat，
 * 主题用平台 Material（见 res/values/themes.xml），这样模块可以单独编译、单独测。
 */
class VideoPlayerActivity : Activity(), PlayerControlsView.Listener, PlayerGestureController.Host {

    private lateinit var playerView: PlayerView
    private lateinit var controls: PlayerControlsView
    private lateinit var buffering: ProgressBar
    private lateinit var errorPanel: View
    private lateinit var errorText: TextView

    private lateinit var prefs: PlayerPrefs
    private lateinit var gestures: PlayerGestureController
    private lateinit var pip: PipController

    private var player: ExoPlayer? = null
    private lateinit var source: PlaybackSource
    private var options: PlaybackOptions = PlaybackOptions.DEFAULT

    private var speedBeforeBoost = 1f
    private var videoAspect: Pair<Int, Int>? = null
    private var startPositionMs = 0L
    private var lastError: PlaybackException? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val parsed = PlaybackIntents.readSource(intent)
        if (parsed == null) {
            finish()
            return
        }
        source = parsed
        options = PlaybackIntents.readOptions(intent)

        setContentView(R.layout.activity_video_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        prefs = PlayerPrefs(this)
        pip = PipController(this)
        gestures = PlayerGestureController(this, this).apply {
            forwardMs = FORWARD_MS
            backwardMs = BACKWARD_MS
            boostSpeed = BOOST_SPEED
        }

        playerView = findViewById(R.id.player_view)
        controls = findViewById(R.id.controls)
        buffering = findViewById(R.id.buffering)
        errorPanel = findViewById(R.id.error_panel)
        errorText = findViewById(R.id.error_text)

        playerView.useController = false
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        playerView.setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
        playerView.keepScreenOn = true

        findViewById<View>(R.id.gesture_layer).setOnTouchListener(gestures)
        controls.listener = this
        controls.setSkipPoints(options.skipIntroMs, options.skipOutroMs)

        findViewById<View>(R.id.btn_external).setOnClickListener { openExternal() }
        findViewById<View>(R.id.btn_retry).setOnClickListener { retry() }

        startPositionMs = resolveStartPosition()
        preparePlayer(startPositionMs)
    }

    private fun resolveStartPosition(): Long {
        if (options.startPositionMs > 0L) return options.startPositionMs
        if (!options.rememberPosition || source.isPrivate || source.live) return 0L
        return prefs.positionOf(source.resumeKey)
    }

    private fun preparePlayer(startMs: Long) {
        releasePlayer()
        errorPanel.visibility = View.GONE
        lastError = null

        val factory = MediaItemFactory(defaultUserAgent())
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(factory.mediaSourceFactory(source))
            .build()
            .apply {
                setAudioAttributes(AudioAttributes.DEFAULT, true)
                setHandleAudioBecomingNoisy(true)
                repeatMode = if (options.loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                playWhenReady = true
            }
        player = exo
        playerView.player = exo
        exo.addListener(playerListener)
        exo.setMediaItem(factory.build(source), startMs.coerceAtLeast(0L))
        exo.prepare()
        exo.setPlaybackSpeed(currentSpeed())

        controls.attach(exo, displayTitle())
        controls.setSpeedLabel(currentSpeed())
        controls.setSkipPoints(options.skipIntroMs, options.skipOutroMs)
        updatePipParams()
    }

    private fun currentSpeed(): Float =
        if (options.rememberSpeed) prefs.playbackSpeed else PlayerPrefs.clampSpeed(options.speed)

    private fun displayTitle(): String =
        source.title?.takeIf { it.isNotBlank() }
            ?: source.pageTitle?.takeIf { it.isNotBlank() }
            ?: runCatching { java.net.URI(source.url).host }.getOrNull()
            ?: getString(R.string.player_error)

    private fun defaultUserAgent(): String = runCatching { WebSettings.getDefaultUserAgent(this) }
        .getOrDefault("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            buffering.visibility =
                if (playbackState == Player.STATE_BUFFERING && player?.playWhenReady == true) View.VISIBLE
                else View.GONE
            if (playbackState == Player.STATE_ENDED) savePosition()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) updatePipParams()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            buffering.visibility =
                if (!isPlaying && player?.playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) updatePipParams()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            videoAspect = PlayerMath.safeAspect(videoSize.width, videoSize.height)
            updatePipParams()
        }

        override fun onPlayerError(error: PlaybackException) {
            lastError = error
            buffering.visibility = View.GONE
            errorText.text = describeError(error)
            errorPanel.visibility = View.VISIBLE
            controls.setControlsVisible(true)
        }
    }

    private fun describeError(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> getString(R.string.player_error_network)

        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> getString(R.string.player_error_unsupported)

        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> getString(R.string.player_error_source)

        else -> getString(R.string.player_error)
    }

    private fun updatePipParams() {
        val autoEnter = options.autoPipOnLeave && (player?.isPlaying == true) && pip.isSupported
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            pip.updateParams(videoAspect, autoEnter = autoEnter)
        } else if (videoAspect != null) {
            pip.updateParams(videoAspect)
        }
    }

    // ---------- PlayerControlsView.Listener ----------

    override fun onPlayPauseClick() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    override fun onSeekTo(positionMs: Long, fromUser: Boolean) {
        player?.seekTo(positionMs.coerceAtLeast(0L))
    }

    override fun onBackClick() {
        finish()
    }

    override fun onPipClick() {
        if (!pip.isSupported) {
            Toast.makeText(this, R.string.player_pip_unsupported, Toast.LENGTH_SHORT).show()
            return
        }
        updatePipParams()
        pip.enter()
    }

    override fun onSpeedClick(anchor: View) {
        val labels = PlayerPrefs.SPEED_STEPS.map { PlayerPrefs.speedLabel(it) }.toTypedArray()
        val current = currentSpeed()
        val checked = PlayerPrefs.SPEED_STEPS.indexOfFirst { PlayerMath.nearlyEquals(it, current) }
        AlertDialog.Builder(this)
            .setTitle(R.string.player_speed_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                applySpeed(PlayerPrefs.SPEED_STEPS[which])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applySpeed(speed: Float) {
        val v = PlayerPrefs.clampSpeed(speed)
        player?.setPlaybackSpeed(v)
        controls.setSpeedLabel(v)
        if (options.rememberSpeed) prefs.playbackSpeed = v
    }

    override fun onFullscreenClick() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    override fun onSkipIntroClick() {
        player?.seekTo(options.skipIntroMs)
        controls.flashFeedback(getString(R.string.player_skip_intro))
    }

    override fun onSkipOutroClick() {
        val duration = player?.duration ?: return
        if (duration > 0) {
            player?.seekTo((duration - options.skipOutroMs).coerceAtLeast(0L))
            controls.flashFeedback(getString(R.string.player_skip_outro))
        }
    }

    override fun onLockChanged(locked: Boolean) {
        if (locked) {
            hideSystemBars()
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ---------- PlayerGestureController.Host ----------

    override fun isLocked(): Boolean = controls.isLocked

    override fun onSingleTap() {
        controls.toggle()
    }

    override fun onSeekBy(deltaMs: Long) {
        val p = player ?: return
        val duration = p.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
        val target = PlayerMath.seekTarget(p.currentPosition, duration, deltaMs)
        p.seekTo(target)
        val seconds = (kotlin.math.abs(deltaMs) / 1000).toInt()
        controls.flashFeedback(
            if (deltaMs >= 0) getString(R.string.player_forward, seconds)
            else getString(R.string.player_backward, seconds)
        )
    }

    override fun onSpeedBoostStart() {
        speedBeforeBoost = player?.playbackParameters?.speed ?: currentSpeed()
        player?.setPlaybackSpeed(gestures.boostSpeed)
        controls.setSpeedLabel(gestures.boostSpeed)
        controls.flashFeedback(
            getString(R.string.player_speed_boost, PlayerPrefs.speedLabel(gestures.boostSpeed))
        )
    }

    override fun onSpeedBoostEnd() {
        player?.setPlaybackSpeed(PlayerMath.restoreSpeed(speedBeforeBoost))
        controls.setSpeedLabel(speedBeforeBoost)
    }

    // ---------- life cycle ----------

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!options.autoPipOnLeave || !pip.isSupported) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return // 系统已自动进入
        updatePipParams()
        pip.enter()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            controls.setAutoHideEnabled(false)
            controls.setControlsVisible(false)
        } else {
            controls.setAutoHideEnabled(true)
            controls.setControlsVisible(true)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newSource = PlaybackIntents.readSource(intent) ?: return
        if (newSource.url == source.url) return
        source = newSource
        options = PlaybackIntents.readOptions(intent)
        startPositionMs = resolveStartPosition()
        controls.setSkipPoints(options.skipIntroMs, options.skipOutroMs)
        preparePlayer(startPositionMs)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onStop() {
        savePosition()
        if (!isInPictureInPictureModeCompat()) releasePlayer()
        super.onStop()
    }

    override fun onDestroy() {
        releasePlayer()
        controls.detach()
        super.onDestroy()
    }

    private fun isInPictureInPictureModeCompat(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode

    private fun savePosition() {
        val p = player ?: return
        if (!options.rememberPosition || source.isPrivate || source.live) return
        val duration = p.duration
        val position = p.currentPosition
        if (duration > 0 && position >= duration - END_THRESHOLD_MS) {
            prefs.clearPosition(source.resumeKey)
        } else if (position > 0) {
            prefs.savePosition(source.resumeKey, position)
        }
    }

    private fun releasePlayer() {
        player?.removeListener(playerListener)
        player?.release()
        player = null
        playerView.player = null
    }

    private fun retry() {
        val position = player?.currentPosition ?: startPositionMs
        preparePlayer(position)
    }

    private fun openExternal() {
        if (!FallbackPlayer.open(this, source)) {
            Toast.makeText(this, R.string.player_no_external, Toast.LENGTH_SHORT).show()
        }
    }

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !controls.isLocked) hideSystemBars()
    }

    companion object {
        private const val FORWARD_MS = 10_000L
        private const val BACKWARD_MS = 10_000L
        private const val BOOST_SPEED = 2.0f
        private const val END_THRESHOLD_MS = 3_000L
    }
}
