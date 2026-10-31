package cn.olfact.app.ui.download

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import cn.olfact.app.R
import cn.olfact.core.api.DownloadTask
import cn.olfact.core.uikit.ListRow

/** 下载列表：ViewHolder 复用，进度更新只重绑可见行。 */
class DownloadAdapter(
    private val onOpen: (DownloadTask) -> Unit,
    private val onDelete: (DownloadTask) -> Unit,
    private val subtitleOf: (DownloadTask) -> String,
    private val trailingOf: (DownloadTask) -> String
) : RecyclerView.Adapter<DownloadAdapter.VH>() {

    private val items = mutableListOf<DownloadTask>()

    fun submit(newItems: List<DownloadTask>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ListRow(parent.context))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val task = items[position]
        holder.row.title = task.name
        holder.row.subtitle = subtitleOf(task)
        holder.row.trailing = trailingOf(task)
        holder.row.setIcon(R.drawable.ic_file)
        holder.row.onRowClick = { onOpen(task) }
        holder.row.setOnLongClickListener {
            onDelete(task)
            true
        }
    }

    class VH(val row: ListRow) : RecyclerView.ViewHolder(row)
}
