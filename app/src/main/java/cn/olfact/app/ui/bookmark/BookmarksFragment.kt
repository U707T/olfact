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
    private var query = ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentEntryListBinding.bind(view)
        binding = b
        b.header.title = "书签"
        b.header.onBack = { Nav.pop() }
        b.header.addAction(R.drawable.ic_bookmark_add, "新增书签") { add() }
        b.header.addAction(R.drawable.ic_search, "搜索书签") {
            Dialogs.input(requireContext(), "搜索书签", query, "标题或网址；留空显示全部") { text ->
                query = text.trim()
                render()
            }
        }

        val a = BookmarkAdapter(
            onOpen = { Nav.openBrowser(it.url) },
            onDelete = { confirmDelete(it) }
        )
        adapter = a
        b.list.layoutManager = LinearLayoutManager(requireContext())
        b.list.adapter = a
        b.list.addItemDecoration(cn.olfact.core.uikit.InsetDivider(requireContext()))
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
        val all = BookmarkStore.all()
        val items = if (query.isBlank()) all else all.filter {
            it.title.contains(query, ignoreCase = true) || it.url.contains(query, ignoreCase = true)
        }
        b.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        if (items.isEmpty()) {
            b.empty.text = if (query.isBlank()) getString(R.string.empty_bookmarks) else "没有匹配「$query」的书签"
            b.empty.setAction(if (query.isBlank()) "新增书签" else "清除搜索") {
                if (query.isBlank()) add() else { query = ""; render() }
            }
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
