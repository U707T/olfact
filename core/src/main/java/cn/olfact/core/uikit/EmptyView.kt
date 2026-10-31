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
 * 空态：大图标 + 一句话 + 可选按钮。列表为空、搜索无结果、下载为空都用它。
 */
class EmptyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val iconView = ImageView(context).apply {
        setImageResource(R.drawable.uikit_ic_file)
        imageTintList = ContextCompat.getColorStateList(context, R.color.uikit_text_hint)
        alpha = 0.45f
        layoutParams = LayoutParams(context.dpF(64f), context.dpF(64f))
    }
    private val textView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.uikit_text_value))
        setTextColor(ContextCompat.getColor(context, R.color.uikit_text_secondary))
        gravity = Gravity.CENTER
        setPadding(context.dpF(32f), context.dpF(12f), context.dpF(32f), 0)
    }
    private val actionView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.uikit_text_value))
        setTextColor(ContextCompat.getColor(context, R.color.uikit_text_accent))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(context.dpF(12f), context.dpF(12f), context.dpF(12f), context.dpF(12f))
        visibility = View.GONE
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(context.dpF(24f), context.dpF(48f), context.dpF(24f), context.dpF(48f))
        addView(iconView)
        addView(textView)
        addView(actionView)
    }

    var text: CharSequence
        get() = textView.text
        set(v) {
            textView.text = v
        }

    fun setIcon(resId: Int) {
        iconView.setImageResource(resId)
    }

    fun setAction(label: String, onClick: () -> Unit) {
        actionView.text = label
        actionView.visibility = View.VISIBLE
        actionView.setOnClickListener { onClick() }
    }
}
