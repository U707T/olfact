package cn.olfact.app.ui.bookmark

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import cn.olfact.app.R
import cn.olfact.app.data.Bookmark
import cn.olfact.app.data.BookmarkStore
import cn.olfact.app.databinding.FragmentEntryListBinding
import cn.olfact.app.nav.Nav
import cn.olfact.app.ui.common.Dialogs
import com.google.android.material.snackbar.Snackbar

/** 书签列表：点开、行尾删除（长按也可）、删除后可撤销。 */
class BookmarksFragment : Fragment(R.layout.fragment_entry_list) {

    private var binding: FragmentEntryListBinding? = null
    private var adapter: BookmarkAdapter? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentEntryListBinding.bind(view)
        binding = b
        b.header.title = "书签"
        b.header.onBack = { Nav.pop() }
        b.header.addAction(R.drawable.ic_bookmark_add, "新增书签") { add() }

        val a = BookmarkAdapter(
            onOpen = { Nav.openBrowser(it.url) },
            onDelete = { confirmDelete(it) }
        )
        adapter = a
        b.list.layoutManager = LinearLayoutManager(requireContext())
        b.list.adapter = a
        render()
    }

    private fun add() {
        Dialogs.input(Nav.context()!!, "新增书签", hint = "https://example.com") { url ->
            if (url.isBlank()) return@input
            BookmarkStore.add(requireContext(), url, url)
            render()
        }
    }

    private fun confirmDelete(bookmark: Bookmark) {
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

    private fun render() {
        val b = binding ?: return
        val a = adapter ?: return
        val items = BookmarkStore.all()
        b.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        if (items.isEmpty()) {
            b.empty.text = getString(R.string.empty_bookmarks)
            b.empty.setAction("新增书签") { add() }
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
