package cn.olfact.player.gesture

import android.annotation.SuppressLint
import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import cn.olfact.player.PlayerMath

/**
 * 只做三件事：双击左右快进/快退、长按倍速、单击显隐控制条。
 * （亮度/音量/进度滑动手势已按方案砍掉。）
 */
class PlayerGestureController(
    context: Context,
    private val host: Host,
) : View.OnTouchListener {

    interface Host {
        fun isLocked(): Boolean
        fun onSingleTap()
        fun onSeekBy(deltaMs: Long)
        fun onSpeedBoostStart()
        fun onSpeedBoostEnd()
    }

    var forwardMs: Long = 10_000L
    var backwardMs: Long = 10_000L
    var boostSpeed: Float = 2.0f

    private var boosting = false
    private var viewWidth = 0
    private var longPressConsumed = false
    private var downX = 0f

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (longPressConsumed) return true
            host.onSingleTap()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (host.isLocked()) return true
            val width = viewWidth.toFloat()
            if (width <= 0f) return true
            downX = e.x
            return true
        }

        override fun onDoubleTapEvent(e: MotionEvent): Boolean {
            if (e.action != MotionEvent.ACTION_DOWN) return true
            if (host.isLocked()) return true
            val width = viewWidth.toFloat()
            if (width <= 0f) return true
            when {
                e.x < width / 3f -> host.onSeekBy(-backwardMs)
                e.x > width * 2f / 3f -> host.onSeekBy(forwardMs)
                else -> Unit // 中间双击不做动作
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (host.isLocked()) return
            longPressConsumed = true
            boosting = true
            host.onSpeedBoostStart()
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (v.width > 0) viewWidth = v.width
        detector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressConsumed = false
                downX = event.x
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (boosting) {
                    boosting = false
                    host.onSpeedBoostEnd()
                }
                longPressConsumed = false
            }
        }
        return true
    }

    /** 双击位置到屏幕边缘的百分比，界面层可用它做左/右的视觉反馈。 */
    fun zoneOf(x: Float, width: Int): Zone = when {
        width <= 0 -> Zone.CENTER
        x < width / 3f -> Zone.LEFT
        x > width * 2f / 3f -> Zone.RIGHT
        else -> Zone.CENTER
    }

    enum class Zone { LEFT, CENTER, RIGHT }

    companion object {
        fun clampSeek(positionMs: Long, durationMs: Long, deltaMs: Long): Long =
            PlayerMath.seekTarget(positionMs, durationMs, deltaMs)
    }
}
