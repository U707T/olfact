package cn.olfact.app.ui.bookmark

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.core.content.ContextCompat
import cn.olfact.app.R
import cn.olfact.app.data.BookmarkStore
import cn.olfact.app.databinding.FragmentEntryListBinding
import cn.olfact.app.nav.Nav
import cn.olfact.app.ui.common.Dialogs
import cn.olfact.core.uikit.ListRow
import cn.olfact.core.uikit.SettingGroup
import com.google.android.material.snackbar.Snackbar

/** 书签列表：点开、长按删、右上角新增。 */
class BookmarksFragment : Fragment(R.layout.fragment_entry_list) {

    private var binding: FragmentEntryListBinding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentEntryListBinding.bind(view)
        binding = b
        b.header.title = "书签"
        b.header.onBack = { Nav.pop() }
        b.header.addAction(R.drawable.ic_bookmark_add, "新增书签") { add() }
        render()
    }

    private fun add() {
        Dialogs.input(Nav.context()!!, "新增书签", hint = "https://example.com") { url ->
            if (url.isBlank()) return@input
            BookmarkStore.add(requireContext(), url, url)
            render()
        }
    }

    private fun render() {
        val b = binding ?: return
        val ctx = requireContext()
        val items = BookmarkStore.all()
        b.list.removeAllViews()
        b.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        if (items.isEmpty()) {
            b.empty.text = getString(R.string.empty_bookmarks)
            b.empty.setAction("新增书签") { add() }
            return
        }

        val group = SettingGroup(ctx)
        items.forEach { bookmark ->
            val row = ListRow(ctx).apply {
                title = bookmark.title
                subtitle = bookmark.url
                setIcon(R.drawable.ic_bookmark)
                showChevron(false)
                onRowClick = { Nav.openBrowser(bookmark.url) }
                setOnLongClickListener { confirmDelete(bookmark); true }
            }
            // 可见的删除入口：不再只能靠长按猜。
            val del = android.widget.ImageView(ctx).apply {
                setImageResource(R.drawable.ic_trash)
                imageTintList = ContextCompat.getColorStateList(ctx, R.color.text_hint)
                contentDescription = "删除书签"
                val pad = (10 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
                setOnClickListener { confirmDelete(bookmark) }
            }
            row.addTrailing(del)
            group.addRow(row, dividerInsetDp = 52)
        }
        b.list.addView(group)
    }

    private fun confirmDelete(bookmark: cn.olfact.app.data.Bookmark) {
        val ctx = requireContext()
        Dialogs.confirm(ctx, "删除书签", bookmark.title, danger = true) {
            BookmarkStore.remove(ctx, bookmark.id)
            render()
            Snackbar.make(requireView(), "已删除「${bookmark.title}」", Snackbar.LENGTH_LONG)
                .setAction("撤销") {
                    BookmarkStore.restore(ctx, bookmark)
                    render()
                }
                .show()
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
