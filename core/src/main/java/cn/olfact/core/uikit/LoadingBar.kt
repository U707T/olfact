package cn.olfact.core.uikit

import cn.olfact.core.R
import android.content.Context
import android.util.AttributeSet

import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.core.content.ContextCompat

/**
 * 顶部 2dp 细进度条（页面加载、订阅更新、备份）。
 * 不用 Material 的 LinearProgressIndicator，省一个依赖。
 */
class LoadingBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate = true
        indeterminateTintList = ContextCompat.getColorStateList(context, R.color.uikit_accent)
        minimumHeight = context.dpF(2f)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, context.dpF(2f))
    }

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, context.dpF(2f))
        addView(bar)
        visibility = GONE
    }

    fun show() {
        visibility = VISIBLE
    }

    fun hide() {
        visibility = GONE
    }
}
