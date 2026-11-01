package cn.olfact.core.uikit

import cn.olfact.core.R
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * 锚定在某个 View 上的白色菜单浮层。
 *
 * 原版大量使用这种形态：下载列表 ⋮ 的六项菜单、底栏长按的「回到主页 / 复制链接 …」、
 * 标签数长按的「新建窗口 / 清除其它窗口 / 清除全部窗口」，都是 `XPopup.asAttachList`
 * ——白色圆角、行高约 52dp、无分隔线，锚在按钮下方。
 *
 * 用 PopupWindow 自己画，避免为了一个浮层引入 XPopup 这类第三方库。
 */
object AnchoredMenu {

    private var current: PopupWindow? = null

    fun dismiss() {
        current?.takeIf { it.isShowing }?.dismiss()
        current = null
    }

    /**
     * @param anchor 锚点视图（浮层出现在它下方，右侧对齐）
     * @param items 文案列表
     * @param onPick 选中回调（索引）
     */
    fun show(anchor: View, items: List<String>, onPick: (Int) -> Unit) {
        if (items.isEmpty()) return
        val context = anchor.context
        dismiss()

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = context.dpF(12f).toFloat()
                setColor(ContextCompat.getColor(context, R.color.uikit_card))
            }
            elevation = context.dpF(6f).toFloat()
            setPadding(0, context.dpF(4f), 0, context.dpF(4f))
        }

        var popup: PopupWindow? = null
        items.forEachIndexed { index, label ->
            column.addView(TextView(context).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(ContextCompat.getColor(context, R.color.uikit_text_primary))
                gravity = Gravity.CENTER_VERTICAL
                setPadding(context.dpF(18f), context.dpF(14f), context.dpF(18f), context.dpF(14f))
                isClickable = true
                background = ContextCompat.getDrawable(context, R.drawable.uikit_ripple_row)
                setOnClickListener {
                    popup?.dismiss()
                    onPick(index)
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val width = context.dpF(150f).toInt()
        column.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val height = column.measuredHeight.coerceAtLeast(context.dpF(40f).toInt())

        popup = PopupWindow(column, width, height, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            isFocusable = true
            elevation = context.dpF(6f).toFloat()
        }
        current = popup
        popup.setOnDismissListener { if (current === popup) current = null }

        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val metrics = context.resources.displayMetrics
        val x = (location[0] + anchor.width - width).coerceIn(0, (metrics.widthPixels - width).coerceAtLeast(0))
        val below = location[1] + anchor.height + context.dpF(4f).toInt()
        // 下方放不下就往上弹，避免菜单跑出屏幕。
        val y = if (below + height > metrics.heightPixels) {
            (location[1] - height - context.dpF(4f).toInt()).coerceAtLeast(0)
        } else {
            below
        }
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
    }
}
