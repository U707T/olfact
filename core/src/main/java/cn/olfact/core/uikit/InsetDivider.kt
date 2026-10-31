package cn.olfact.core.uikit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import cn.olfact.core.R

/**
 * 列表分隔线：左侧留出图标宽度（与 [SettingGroup] 的 dividerInsetDp 对齐）。
 * RecyclerView 换掉 LinearLayout 后，分隔线不能再用 SettingGroup 自带的那条。
 */
class InsetDivider(
    context: Context,
    private val insetDp: Int = 52
) : RecyclerView.ItemDecoration() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.uikit_divider)
        strokeWidth = context.dpF(1f).coerceAtLeast(1).toFloat()
    }
    private val inset = context.dpF(insetDp.toFloat())

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (parent.layoutManager == null) return
        val left = (parent.paddingLeft + inset).toFloat()
        val right = (parent.width - parent.paddingRight).toFloat()
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val lp = child.layoutParams as? RecyclerView.LayoutParams ?: continue
            val position = lp.viewAdapterPosition
            if (position == RecyclerView.NO_POSITION || position >= state.itemCount - 1) continue
            val y = (child.bottom + lp.bottomMargin).toFloat()
            c.drawLine(left, y, right, y, paint)
        }
    }
}
