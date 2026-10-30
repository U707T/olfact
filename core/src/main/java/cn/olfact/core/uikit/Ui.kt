package cn.olfact.core.uikit

import cn.olfact.core.R
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat

/** dp → px */
fun Context.dpF(value: Float): Int = (value * resources.displayMetrics.density).toInt()

fun Context.colorOf(@ColorRes id: Int): Int = ContextCompat.getColor(this, id)

fun Context.drawableOf(@DrawableRes id: Int) = ContextCompat.getDrawable(this, id)

/** 圆角纯色背景，避免为每个颜色都建一个 shape.xml。 */
fun roundedBg(color: Int, radiusDp: Float = 16f, strokeColor: Int? = null, strokeDp: Float = 0f): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radiusDp * 1f
        if (strokeColor != null && strokeDp > 0f) setStroke(strokeDp.toInt().coerceAtLeast(1), strokeColor)
    }

/** 统一的分隔线：1px，左侧缩进，颜色跟随主题。 */
fun Context.divider(insetStartDp: Int = 16): View = View(this).apply {
    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.density).toInt().coerceAtLeast(1))
    setBackgroundColor(colorOf(R.color.uikit_divider))
    if (insetStartDp > 0) {
        val pad = dpF(insetStartDp.toFloat())
        setPadding(pad, 0, 0, 0)
    }
}

fun Context.textView(
    text: CharSequence = "",
    sizeSp: Float = 15f,
    color: Int = colorOf(R.color.uikit_text_primary),
    bold: Boolean = false
): TextView = TextView(this).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color)
    if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
}

fun Context.iconView(@DrawableRes res: Int, sizeDp: Int = 24, tint: Int? = null): ImageView =
    ImageView(this).apply {
        setImageResource(res)
        if (tint != null) imageTintList = android.content.res.ColorStateList.valueOf(tint)
        layoutParams = LinearLayout.LayoutParams(dpF(sizeDp.toFloat()), dpF(sizeDp.toFloat()))
        scaleType = ImageView.ScaleType.FIT_CENTER
    }
