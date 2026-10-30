package cn.olfact.core.uikit

import cn.olfact.core.R
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.ContextCompat

/**
 * 白色圆角卡片容器。设置页的一「组」行放一个 Group，组之间留 12dp。
 *
 * 自带行间分隔线（首行前、末行后不加），避免每个页面手写 divider。
 */
class SettingGroup @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private var rowCount = 0

    init {
        orientation = VERTICAL
        background = ContextCompat.getDrawable(context, R.drawable.uikit_bg_card)
        setPadding(0, context.dpF(4f), 0, context.dpF(4f))
    }

    fun addRow(row: View, dividerInsetDp: Int = 16): SettingGroup {
        if (rowCount > 0) {
            addView(
                View(context).apply {
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 1).apply {
                        marginStart = context.dpF(dividerInsetDp.toFloat())
                    }
                    setBackgroundColor(ContextCompat.getColor(context, R.color.uikit_divider))
                }
            )
        }
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        rowCount++
        return this
    }

    /** 直接加一条整宽的普通视图（如说明文字、空态）。 */
    fun addPlain(view: View): SettingGroup {
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        rowCount++
        return this
    }

    fun clearRows() {
        removeAllViews()
        rowCount = 0
    }
}
