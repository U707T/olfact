package cn.olfact.app.ui.player

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import cn.olfact.app.R
import cn.olfact.app.data.FavoriteStore
import cn.olfact.app.databinding.FragmentEntryListBinding
import cn.olfact.app.nav.Nav
import cn.olfact.app.ui.common.Dialogs
import cn.olfact.core.uikit.ListRow
import cn.olfact.core.uikit.SettingGroup

/** 收藏（媒体）：播放页收藏的内容在这里。 */
class FavoritesFragment : Fragment(R.layout.fragment_entry_list) {

    private var binding: FragmentEntryListBinding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentEntryListBinding.bind(view)
        binding = b
        b.header.title = "收藏"
        b.header.onBack = { Nav.pop() }
        render()
    }

    private fun render() {
        val b = binding ?: return
        val ctx = requireContext()
        val items = FavoriteStore.items
        b.list.removeAllViews()
        b.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        if (items.isEmpty()) {
            b.empty.text = "还没有收藏的媒体"
            return
        }
        val group = SettingGroup(ctx)
        items.forEach { f ->
            group.addRow(ListRow(ctx).apply {
                title = f.title
                subtitle = f.url
                setIcon(R.drawable.ic_star)
                onRowClick = { Nav.push(PlayerFragment.newInstance(f.url, f.title)) }
                setOnLongClickListener {
                    Dialogs.confirm(ctx, "取消收藏", f.title, danger = true) {
                        FavoriteStore.remove(ctx, f.id)
                        render()
                    }
                    true
                }
            }, dividerInsetDp = 52)
        }
        b.list.addView(group)
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
