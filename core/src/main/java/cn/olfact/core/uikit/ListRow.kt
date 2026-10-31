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
 * 通用列表行：`[图标] 标题 / 副标题 ........ 尾部文字 [箭头]`。
 * 书签、历史、下载、脚本、AI 供应商都用它，保证列表视觉一致。
 */
class ListRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val iconView = ImageView(context).apply {
        visibility = View.GONE
        layoutParams = LayoutParams(context.dpF(22f), context.dpF(22f)).apply { marginEnd = context.dpF(14f) }
        scaleType = ImageView.ScaleType.FIT_CENTER
    }
    private val titleView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.uikit_text_row))
        setTextColor(ContextCompat.getColor(context, R.color.uikit_text_primary))
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val subtitleView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.uikit_text_small))
        setTextColor(ContextCompat.getColor(context, R.color.uikit_text_secondary))
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        visibility = View.GONE
    }
    private val trailingView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.uikit_text_small))
        setTextColor(ContextCompat.getColor(context, R.color.uikit_text_secondary))
        gravity = Gravity.END
    }
    private val chevronView = ImageView(context).apply {
        setImageResource(R.drawable.uikit_ic_chevron_right)
        imageTintList = ContextCompat.getColorStateList(context, R.color.uikit_text_hint)
        visibility = View.GONE
        layoutParams = LayoutParams(context.dpF(16f), context.dpF(16f)).apply { marginStart = context.dpF(6f) }
    }

    var onRowClick: (() -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dpF(64f)
        setPadding(context.dpF(16f), context.dpF(10f), context.dpF(16f), context.dpF(10f))
        setBackgroundResource(R.drawable.uikit_ripple_row)

        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(titleView)
            addView(subtitleView)
        }
        addView(iconView)
        addView(column, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(trailingView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = context.dpF(8f)
        })
        addView(chevronView)

        setOnClickListener { onRowClick?.invoke() }
    }

    var title: String
        get() = titleView.text.toString()
        set(v) {
            titleView.text = v
        }

    var subtitle: String?
        get() = subtitleView.text?.toString()?.ifEmpty { null }
        set(v) {
            subtitleView.text = v
            subtitleView.visibility = if (v.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

    var trailing: String?
        get() = trailingView.text?.toString()?.ifEmpty { null }
        set(v) {
            trailingView.text = v
            trailingView.visibility = if (v.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

    fun setIcon(resId: Int) {
        iconView.setImageResource(resId)
        iconView.imageTintList = ContextCompat.getColorStateList(context, R.color.uikit_text_primary)
        iconView.visibility = View.VISIBLE
    }

    /** 保留图标自带颜色（原版的彩色图标，例如 AI 的绿色）。 */
    fun setIconOriginal(resId: Int) {
        iconView.setImageResource(resId)
        iconView.imageTintList = null
        iconView.visibility = View.VISIBLE
    }

    fun setIconTint(color: Int) {
        iconView.imageTintList = android.content.res.ColorStateList.valueOf(color)
    }

    fun showChevron(show: Boolean = true) {
        chevronView.visibility = if (show) View.VISIBLE else View.GONE
    }

    /** 尾部加一个自定义视图（开关、进度等）。 */
    fun addTrailing(view: View) {
        addView(view, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = context.dpF(8f)
        })
    }
}
