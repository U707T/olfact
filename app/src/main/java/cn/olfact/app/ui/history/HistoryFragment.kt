package cn.olfact.app.ui.history

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import cn.olfact.app.R
import cn.olfact.app.data.HistoryEntry
import cn.olfact.app.data.HistoryStore
import cn.olfact.app.databinding.FragmentEntryListBinding
import cn.olfact.app.nav.Nav
import cn.olfact.app.ui.common.Dialogs
import com.google.android.material.snackbar.Snackbar
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 历史：按「今天 / 昨天 / 更早」分组，长按删除单条（可撤销）。 */
class HistoryFragment : Fragment(R.layout.fragment_entry_list) {

    private var binding: FragmentEntryListBinding? = null
    private var adapter: HistoryAdapter? = null
    private var query = ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentEntryListBinding.bind(view)
        binding = b
        b.header.title = "历史"
        b.header.onBack = { Nav.pop() }
        b.header.addAction(R.drawable.ic_trash, "清空历史") {
            Dialogs.confirm(requireContext(), "清空历史", "将删除全部浏览记录，不可恢复", danger = true) {
                HistoryStore.clear(requireContext())
                render()
            }
        }
        b.header.addAction(R.drawable.ic_search, "搜索历史") {
            Dialogs.input(requireContext(), "搜索历史", query, "标题或网址；留空显示全部") { text ->
                query = text.trim()
                render()
            }
        }

        val a = HistoryAdapter(
            onOpen = { Nav.openBrowser(it.url) },
            onDelete = { confirmDelete(it) }
        )
        adapter = a
        b.list.layoutManager = LinearLayoutManager(requireContext())
        b.list.adapter = a
        b.list.addItemDecoration(cn.olfact.core.uikit.InsetDivider(requireContext()))
        render()
    }

    private fun confirmDelete(entry: HistoryEntry) {
        val ctx = requireContext()
        Dialogs.confirm(ctx, "删除记录", entry.title, danger = true) {
            HistoryStore.remove(ctx, entry.url)
            render()
            Snackbar.make(requireView(), "已删除这条记录", Snackbar.LENGTH_LONG)
                .setAction("撤销") {
                    HistoryStore.restore(ctx, entry)
                    render()
                }
                .show()
        }
    }

    private fun render() {
        val b = binding ?: return
        val a = adapter ?: return
        val all = HistoryStore.items
        val items = if (query.isBlank()) all else all.filter {
            it.title.contains(query, ignoreCase = true) || it.url.contains(query, ignoreCase = true)
        }
        b.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        if (items.isEmpty()) {
            b.empty.text = if (query.isBlank()) getString(R.string.empty_history) else "没有匹配「$query」的记录"
            b.empty.setAction(if (query.isBlank()) "去浏览" else "清除搜索") {
                if (query.isBlank()) Nav.openBrowser(null) else { query = ""; render() }
            }
            a.submit(emptyList())
            return
        }

        val today = startOfDay(0)
        val yesterday = startOfDay(-1)
        val groups = linkedMapOf<String, MutableList<HistoryEntry>>()
        items.forEach { e ->
            val label = when {
                e.visitedAt >= today -> "今天"
                e.visitedAt >= yesterday -> "昨天"
                else -> SimpleDateFormat("MM月dd日", Locale.getDefault()).format(Date(e.visitedAt))
            }
            groups.getOrPut(label) { mutableListOf() }.add(e)
        }

        val rows = ArrayList<HistoryAdapter.Item>(items.size + groups.size)
        groups.forEach { (label, entries) ->
            rows.add(HistoryAdapter.Item.Header(label))
            entries.forEach { rows.add(HistoryAdapter.Item.Entry(it)) }
        }
        a.submit(rows)
    }

    private fun startOfDay(offset: Int): Long {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, offset)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    override fun onDestroyView() {
        binding = null
        adapter = null
        super.onDestroyView()
    }
}
