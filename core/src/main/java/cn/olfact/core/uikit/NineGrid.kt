package cn.olfact.core.uikit

import cn.olfact.core.R
import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * 主菜单九宫格（原版是 5 列 × 4 行，见 docs/refs/09-主菜单九宫格.jpg）。
 */
class NineGrid @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GridLayout(context, attrs) {

    data class Item(val icon: Int, val label: String)

    init {
        columnCount = 5
        setPadding(0, context.dpF(8f), 0, context.dpF(8f))
    }

    fun setItems(items: List<Item>, onClick: (Int) -> Unit) {
        removeAllViews()
        items.forEachIndexed { index, item ->
            addView(buildCell(item, index, onClick), cellParams())
        }
    }

    private fun cellParams() = LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
        width = 0
        height = context.dpF(78f)
    }

    private fun buildCell(item: Item, index: Int, onClick: (Int) -> Unit): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            setBackgroundResource(R.drawable.uikit_ripple_row)
            addView(ImageView(context).apply {
                setImageResource(item.icon)
                imageTintList = ContextCompat.getColorStateList(context, R.color.uikit_text_primary)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(context.dpF(26f), context.dpF(26f)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            })
            addView(TextView(context).apply {
                text = item.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(ContextCompat.getColor(context, R.color.uikit_text_primary))
                gravity = Gravity.CENTER
                maxLines = 2
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = context.dpF(7f)
                }
            })
            setOnClickListener { onClick(index) }
        }
    }
}
