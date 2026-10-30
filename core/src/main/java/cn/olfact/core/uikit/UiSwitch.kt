package cn.olfact.core.uikit

import cn.olfact.core.R
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat

/**
 * 极简开关：26dp 高、44dp 宽，轨道圆角，滑块 20dp。
 *
 * 不用 framework `Switch`：不同 ROM 上它的 thumb/track 尺寸不一致，视觉验收过不去。
 */
class UiSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var fraction = 0f
    private var animator: ValueAnimator? = null

    var isChecked: Boolean = false
        private set

    var onCheckedChange: ((Boolean) -> Unit)? = null

    private val widthDp = 44f
    private val heightDp = 26f
    private val thumbInset = 3f

    private val colorOn = ContextCompat.getColor(context, R.color.uikit_accent)
    private val colorOff = ContextCompat.getColor(context, R.color.uikit_divider)
    private val colorThumb = ContextCompat.getColor(context, R.color.uikit_white)

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener { toggle() }
    }

    fun setChecked(checked: Boolean, animate: Boolean = true) {
        if (isChecked == checked) return
        isChecked = checked
        if (animate) {
            animator?.cancel()
            animator = ValueAnimator.ofFloat(fraction, if (checked) 1f else 0f).apply {
                duration = 160
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    fraction = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            fraction = if (checked) 1f else 0f
            invalidate()
        }
    }

    fun toggle() {
        setChecked(!isChecked)
        onCheckedChange?.invoke(isChecked)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (widthDp * resources.displayMetrics.density).toInt()
        val h = (heightDp * resources.displayMetrics.density).toInt()
        setMeasuredDimension(
            resolveSize(w + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(h + paddingTop + paddingBottom, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val h = heightDp * d
        val w = widthDp * d
        val top = (this.height - h) / 2f
        rect.set(0f, top, w, top + h)

        trackPaint.color = blend(colorOff, colorOn, fraction)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, trackPaint)

        val inset = thumbInset * d
        val r = (h - inset * 2) / 2f
        val cx = inset + r + (w - (inset * 2 + r * 2)) * fraction
        thumbPaint.color = colorThumb
        canvas.drawCircle(cx, top + h / 2f, r, thumbPaint)
    }

    private fun blend(from: Int, to: Int, f: Float): Int {
        val a = (android.graphics.Color.alpha(from) + (android.graphics.Color.alpha(to) - android.graphics.Color.alpha(from)) * f).toInt()
        val r = (android.graphics.Color.red(from) + (android.graphics.Color.red(to) - android.graphics.Color.red(from)) * f).toInt()
        val g = (android.graphics.Color.green(from) + (android.graphics.Color.green(to) - android.graphics.Color.green(from)) * f).toInt()
        val b = (android.graphics.Color.blue(from) + (android.graphics.Color.blue(to) - android.graphics.Color.blue(from)) * f).toInt()
        return android.graphics.Color.argb(a, r, g, b)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
