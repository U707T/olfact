package cn.olfact.app.ui.player

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import cn.olfact.app.R
import cn.olfact.app.data.FavoriteStore
import cn.olfact.app.data.FavoriteStore.Favorite
import cn.olfact.app.databinding.FragmentEntryListBinding
import cn.olfact.app.nav.Nav
import cn.olfact.app.ui.common.Dialogs
import com.google.android.material.snackbar.Snackbar

/** 收藏（媒体）：播放页收藏的内容在这里。 */
class FavoritesFragment : Fragment(R.layout.fragment_entry_list) {

    private var binding: FragmentEntryListBinding? = null
    private var adapter: FavoritesAdapter? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentEntryListBinding.bind(view)
        binding = b
        b.header.title = "收藏"
        b.header.onBack = { Nav.pop() }

        val a = FavoritesAdapter(
            onOpen = { Nav.push(PlayerFragment.newInstance(it.url, it.title)) },
            onDelete = { confirmDelete(it) }
        )
        adapter = a
        b.list.layoutManager = LinearLayoutManager(requireContext())
        b.list.adapter = a
        b.list.addItemDecoration(cn.olfact.core.uikit.InsetDivider(requireContext()))
        render()
    }

    private fun confirmDelete(favorite: Favorite) {
        val ctx = requireContext()
        Dialogs.confirm(ctx, "取消收藏", favorite.title, danger = true) {
            FavoriteStore.remove(ctx, favorite.id)
            render()
            Snackbar.make(requireView(), "已取消收藏", Snackbar.LENGTH_LONG)
                .setAction("撤销") {
                    FavoriteStore.restore(ctx, favorite)
                    render()
                }
                .show()
        }
    }

    private fun render() {
        val b = binding ?: return
        val a = adapter ?: return
        val items = FavoriteStore.items.toList()
        b.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        if (items.isEmpty()) {
            b.empty.text = "还没有收藏的媒体"
            a.submit(emptyList())
            return
        }
        a.submit(items)
    }

    override fun onDestroyView() {
        binding = null
        adapter = null
        super.onDestroyView()
    }
}
