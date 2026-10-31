package cn.olfact.app.ui.player

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import cn.olfact.app.R
import cn.olfact.app.data.FavoriteStore.Favorite
import cn.olfact.core.uikit.ListRow

/** 收藏（媒体）列表。 */
class FavoritesAdapter(
    private val onOpen: (Favorite) -> Unit,
    private val onDelete: (Favorite) -> Unit
) : RecyclerView.Adapter<FavoritesAdapter.VH>() {

    private val items = mutableListOf<Favorite>()

    fun submit(newItems: List<Favorite>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ListRow(parent.context))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.row.title = item.title
        holder.row.subtitle = item.url
        holder.row.setIcon(R.drawable.ic_star)
        holder.row.onRowClick = { onOpen(item) }
        holder.row.setOnLongClickListener {
            onDelete(item)
            true
        }
    }

    class VH(val row: ListRow) : RecyclerView.ViewHolder(row)
}
