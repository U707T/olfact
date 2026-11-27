package cn.olfact.app.ui.browser

import android.app.Activity
import android.graphics.Rect
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import cn.olfact.app.R
import cn.olfact.app.data.HistoryStore
import cn.olfact.app.link.LinkCandidate
import cn.olfact.app.link.LinkExtractor
import cn.olfact.core.uikit.ListRow
import cn.olfact.core.uikit.dpF

/**
 * 地址栏下拉：文本提链候选 + 搜索 + 历史。
 *
 * 「粘一段含 3 个链接的文本 → 出 3 条候选且能批量打开」就是这里实现的（验收项）。
 */
class UrlSuggestPopup(
    private val activity: Activity,
    private val anchor: View,
    /** true = 向上弹（地址条贴在底栏上方时用），false = 向下弹（首页搜索框）。 */
    private val above: Boolean = false
) {

    private val column = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(cn.olfact.core.R.drawable.uikit_bg_card)
        elevation = activity.dpF(8f).toFloat()
        setPadding(0, activity.dpF(4f), 0, activity.dpF(4f))
    }

    private val scroll = ScrollView(activity).apply {
        addView(column)
        isFillViewport = false
    }

    private val popup = PopupWindow(
        scroll,
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        false
    ).apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        isOutsideTouchable = true
        isFocusable = false
        inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
        elevation = activity.dpF(8f).toFloat()
    }

    private var layoutObserver: ViewTreeObserver? = null
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { positionPopup() }
    private var geometry: List<Int>? = null

    init {
        popup.setOnDismissListener {
            layoutObserver?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layoutListener)
            layoutObserver = null
            geometry = null
        }
    }

    var onOpen: ((String) -> Unit)? = null
    var onOpenAll: ((List<LinkCandidate>) -> Unit)? = null
    var onSearch: ((String) -> Unit)? = null

    fun show(query: String) {
        val text = query.trim()
        if (text.isEmpty() || !anchor.isAttachedToWindow || !anchor.isShown || anchor.width <= 0) {
            dismiss()
            return
        }

        column.removeAllViews()
        scroll.scrollTo(0, 0)
        val candidates = LinkExtractor.extract(text)
        if (candidates.isNotEmpty()) {
            if (candidates.size > 1) {
                column.addView(header("识别到 ${candidates.size} 个链接，点一条打开，或用「全部打开」"))
                column.addView(ListRow(activity).apply {
                    title = "全部打开（${candidates.size}）"
                    setIcon(R.drawable.ic_tabs)
                    onRowClick = {
                        dismiss()
                        onOpenAll?.invoke(candidates)
                    }
                })
            }
            candidates.forEach { c ->
                column.addView(ListRow(activity).apply {
                    title = c.label
                    subtitle = c.url
                    setIcon(R.drawable.ic_globe)
                    onRowClick = {
                        dismiss()
                        onOpen?.invoke(c.url)
                    }
                })
            }
            column.addView(divider())
        }

        column.addView(ListRow(activity).apply {
            title = "搜索「$text」"
            setIcon(R.drawable.ic_search)
            onRowClick = {
                dismiss()
                onSearch?.invoke(text)
            }
        })

        val history = HistoryStore.search(text, 3)
        if (history.isNotEmpty()) column.addView(divider())
        history.forEach { h ->
            column.addView(ListRow(activity).apply {
                title = h.title
                subtitle = h.url
                setIcon(R.drawable.ic_history)
                onRowClick = {
                    dismiss()
                    onOpen?.invoke(h.url)
                }
            })
        }

        positionPopup()
    }

    private fun positionPopup() {
        if (!anchor.isAttachedToWindow || !anchor.isShown) {
            dismiss()
            return
        }
        // The visible window excludes the keyboard and system bars and has screen coordinates.
        val frame = Rect()
        anchor.getWindowVisibleDisplayFrame(frame)
        val anchorScreen = IntArray(2).also(anchor::getLocationOnScreen)
        val spacing = activity.dpF(4f)
        val availableAbove = anchorScreen[1] - frame.top - spacing
        val availableBelow = frame.bottom - anchorScreen[1] - anchor.height - spacing
        val maxHeight = activity.dpF(360f)
        val width = minOf(anchor.width, frame.width())
        if (width <= 0) {
            dismiss()
            return
        }
        column.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val desiredHeight = minOf(column.measuredHeight, maxHeight)
        val useAbove = if (above) availableAbove >= minOf(desiredHeight, availableBelow)
            else availableBelow < minOf(desiredHeight, availableAbove)
        val space = if (useAbove) availableAbove else availableBelow
        if (space < minOf(desiredHeight, activity.dpF(48f))) {
            dismiss()
            return
        }
        val height = minOf(desiredHeight, space)
        val x = anchorScreen[0].coerceIn(frame.left, frame.right - width) - anchorScreen[0]
        val y = if (useAbove) -(anchor.height + spacing + height) else spacing
        val newGeometry = listOf(x, y, width, height)
        if (geometry == newGeometry && popup.isShowing) return
        geometry = newGeometry
        if (popup.isShowing) {
            popup.update(anchor, x, y, width, height)
        } else {
            popup.width = width
            popup.height = height
            popup.showAsDropDown(anchor, x, y, Gravity.LEFT)
            layoutObserver = anchor.viewTreeObserver.also { it.addOnGlobalLayoutListener(layoutListener) }
        }
    }

    fun dismiss() {
        if (popup.isShowing) popup.dismiss()
    }

    private fun header(text: String) = TextView(activity).apply {
        this.text = text
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
        setPadding(activity.dpF(16f), activity.dpF(10f), activity.dpF(16f), activity.dpF(4f))
    }

    private fun divider() = View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
            topMargin = activity.dpF(4f); bottomMargin = activity.dpF(4f)
        }
        setBackgroundColor(ContextCompat.getColor(activity, R.color.divider))
    }
}
