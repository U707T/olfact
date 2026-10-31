package cn.olfact.app.ui.history

import android.util.TypedValue
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import cn.olfact.app.R
import cn.olfact.app.data.HistoryEntry
import cn.olfact.core.uikit.ListRow

/**
 * 历史列表：日期分组 header + 记录行。
 *
 * 旧实现每次渲染 `forEach { addView }`，1000 条约 6000 个 View 一次性建在主线程；
 * 换成 RecyclerView 后 ViewHolder 复用，滚动不再随条目数变慢。
 */
class HistoryAdapter(
    private val onOpen: (HistoryEntry) -> Unit,
    private val onDelete: (HistoryEntry) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed interface Item {
        data class Header(val text: String) : Item
        data class Entry(val value: HistoryEntry) : Item
    }

    private val items = mutableListOf<Item>()

    fun submit(newItems: List<Item>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int =
        if (items[position] is Item.Header) TYPE_HEADER else TYPE_ROW

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val ctx = parent.context
        return if (viewType == TYPE_HEADER) {
            HeaderVH(TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                setPadding(ctx.dp(4), ctx.dp(14), ctx.dp(4), ctx.dp(6))
            })
        } else {
            RowVH(ListRow(ctx))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is Item.Header -> (holder as HeaderVH).text.text = item.text
            is Item.Entry -> (holder as RowVH).bind(item.value)
        }
    }

    inner class HeaderVH(val text: TextView) : RecyclerView.ViewHolder(text)

    inner class RowVH(private val row: ListRow) : RecyclerView.ViewHolder(row) {
        fun bind(entry: HistoryEntry) {
            row.title = entry.title
            row.subtitle = entry.url
            row.setIcon(R.drawable.ic_history)
            row.onRowClick = { onOpen(entry) }
            row.setOnLongClickListener {
                onDelete(entry)
                true
            }
        }
    }

    private fun android.content.Context.dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ROW = 1
    }
}
