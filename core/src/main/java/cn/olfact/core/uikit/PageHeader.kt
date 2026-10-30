package cn.olfact.core.uikit

import cn.olfact.core.R
import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * 大标题页头：第一行返回箭头，第二行 28sp 粗体标题。
 *
 * 原版设置子页就是这个结构（见 docs/refs/18-设置-基本设置.jpg）。
 */
class PageHeader @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val backButton = ImageView(context).apply {
        setImageResource(R.drawable.uikit_ic_back)
        imageTintList = ContextCompat.getColorStateList(context, R.color.uikit_text_primary)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(context.dpF(12f), context.dpF(12f), context.dpF(12f), context.dpF(12f))
        layoutParams = LayoutParams(context.dpF(48f), context.dpF(48f))
        contentDescription = "返回"
        setOnClickListener { onBack?.invoke() }
    }

    private val actionRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
    }

    private val titleView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.uikit_text_page_title))
        setTextColor(ContextCompat.getColor(context, R.color.uikit_text_primary))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(context.dpF(16f), 0, context.dpF(16f), context.dpF(8f))
    }

    var onBack: (() -> Unit)? = null

    var title: CharSequence
        get() = titleView.text
        set(value) {
            titleView.text = value
        }

    init {
        orientation = VERTICAL
        setPadding(0, context.dpF(4f), 0, context.dpF(8f))
        val topRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(backButton)
            addView(actionRow, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        addView(topRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(titleView)
    }

    fun addAction(iconRes: Int, description: String? = null, onClick: () -> Unit) {
        val v = ImageView(context).apply {
            setImageResource(iconRes)
            imageTintList = ContextCompat.getColorStateList(context, R.color.uikit_text_primary)
            setPadding(context.dpF(12f), context.dpF(12f), context.dpF(12f), context.dpF(12f))
            layoutParams = LayoutParams(context.dpF(48f), context.dpF(48f))
            contentDescription = description
            setOnClickListener { onClick() }
        }
        actionRow.addView(v)
    }

    fun hideBack() {
        backButton.visibility = View.GONE
    }
}
