package cn.olfact.core.util

import android.content.Context
import android.content.res.Resources
import kotlin.math.roundToInt

/**
 * dp/sp 换算。设计稿按原版 1:1 抄（见 core 的 dimens.xml），
 * 代码里动态算尺寸时用这里的扩展。
 */
object Dimens {
    fun dpToPx(dp: Float): Float = dp * Resources.getSystem().displayMetrics.density

    fun dpToPx(dp: Int): Int = (dp * Resources.getSystem().displayMetrics.density).roundToInt()

    fun pxToDp(px: Float): Float = px / Resources.getSystem().displayMetrics.density

    fun spToPx(sp: Float): Float = sp * Resources.getSystem().displayMetrics.scaledDensity

    fun pxToSp(px: Float): Float = px / Resources.getSystem().displayMetrics.scaledDensity
}

val Number.dp: Int get() = Dimens.dpToPx(this.toInt())

val Number.dpf: Float get() = Dimens.dpToPx(this.toFloat())

val Number.sp: Float get() = Dimens.spToPx(this.toFloat())

fun Context.dp(dp: Float): Int = (dp * resources.displayMetrics.density).roundToInt()

fun Context.dp(dp: Int): Int = (dp * resources.displayMetrics.density).roundToInt()

fun Context.sp(sp: Float): Float = sp * resources.displayMetrics.scaledDensity
