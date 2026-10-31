package cn.olfact.app.ui.bookmark

import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import cn.olfact.app.R
import cn.olfact.app.data.Bookmark
import cn.olfact.core.uikit.ListRow

/** 书签列表：RecyclerView + 行尾可见删除按钮（长按仍然可用）。 */
class BookmarkAdapter(
    private val onOpen: (Bookmark) -> Unit,
    private val onDelete: (Bookmark) -> Unit
) : RecyclerView.Adapter<BookmarkAdapter.VH>() {

    private val items = mutableListOf<Bookmark>()

    fun submit(newItems: List<Bookmark>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val row = ListRow(ctx).apply { showChevron(false) }
        val del = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_trash)
            imageTintList = ContextCompat.getColorStateList(ctx, R.color.text_hint)
            contentDescription = "删除书签"
            val pad = (10 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        row.addTrailing(del)
        return VH(row, del)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val bookmark = items[position]
        holder.row.title = bookmark.title
        holder.row.subtitle = bookmark.url
        holder.row.setIcon(R.drawable.ic_bookmark)
        holder.row.onRowClick = { onOpen(bookmark) }
        holder.row.setOnLongClickListener {
            onDelete(bookmark)
            true
        }
        holder.del.setOnClickListener { onDelete(bookmark) }
    }

    class VH(val row: ListRow, val del: ImageView) : RecyclerView.ViewHolder(row)
}
