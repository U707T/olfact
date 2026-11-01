package cn.olfact.app.ui.browser

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
        elevation = activity.dpF(8f).toFloat()
    }

    var onOpen: ((String) -> Unit)? = null
    var onOpenAll: ((List<LinkCandidate>) -> Unit)? = null
    var onSearch: ((String) -> Unit)? = null

    fun show(query: String) {
        column.removeAllViews()
        val text = query.trim()
        if (text.isEmpty()) {
            dismiss()
            return
        }

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

        // 高度要按「锚点上方/下方实际可用空间」算，否则固定 360dp 会在小屏或
        // 地址条贴底时被顶出屏幕，候选层直接看不见。
        val metrics = activity.resources.displayMetrics
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val margin = activity.dpF(8f)
        val space = if (above) {
            loc[1] - margin
        } else {
            metrics.heightPixels - (loc[1] + anchor.height) - margin
        }
        val maxHeight = activity.dpF(360f)
        popup.width = anchor.width
        popup.height = space.coerceIn(activity.dpF(96f), maxHeight)

        if (popup.isShowing) {
            popup.update(anchor, 0, 0, -1, -1)
        } else if (above) {
            // 向上弹：底部对齐到锚点上方，列表从下往上长，避免遮住输入框。
            popup.showAsDropDown(anchor, 0, -(anchor.height + popup.height), Gravity.BOTTOM)
        } else {
            popup.showAsDropDown(anchor, 0, activity.dpF(4f))
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
