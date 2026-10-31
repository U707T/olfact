package cn.olfact.app.ui.history

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import cn.olfact.app.R
import cn.olfact.app.data.HistoryStore
import cn.olfact.app.databinding.FragmentEntryListBinding
import cn.olfact.app.nav.Nav
import cn.olfact.app.ui.common.Dialogs
import cn.olfact.core.uikit.ListRow
import cn.olfact.core.uikit.SettingGroup
import com.google.android.material.snackbar.Snackbar
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 历史：按「今天 / 昨天 / 更早」分组，长按删除单条。 */
class HistoryFragment : Fragment(R.layout.fragment_entry_list) {

    private var binding: FragmentEntryListBinding? = null

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
        render()
    }

    private fun render() {
        val b = binding ?: return
        val ctx = requireContext()
        val items = HistoryStore.items
        b.list.removeAllViews()
        b.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        if (items.isEmpty()) {
            b.empty.text = getString(R.string.empty_history)
            return
        }

        val today = startOfDay(0)
        val yesterday = startOfDay(-1)
        val groups = linkedMapOf<String, MutableList<cn.olfact.app.data.HistoryEntry>>()
        items.forEach { e ->
            val label = when {
                e.visitedAt >= today -> "今天"
                e.visitedAt >= yesterday -> "昨天"
                else -> SimpleDateFormat("MM月dd日", Locale.getDefault()).format(Date(e.visitedAt))
            }
            groups.getOrPut(label) { mutableListOf() }.add(e)
        }

        groups.forEach { (label, entries) ->
            b.list.addView(TextView(ctx).apply {
                text = label
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                setPadding(4, dp(14), 4, dp(6))
            })
            val group = SettingGroup(ctx)
            entries.forEach { e ->
                group.addRow(ListRow(ctx).apply {
                    title = e.title
                    subtitle = e.url
                    setIcon(R.drawable.ic_history)
                    onRowClick = { Nav.openBrowser(e.url) }
                    setOnLongClickListener {
                        Dialogs.confirm(ctx, "删除记录", e.title, danger = true) {
                            HistoryStore.remove(ctx, e.url)
                            render()
                            Snackbar.make(requireView(), "已删除这条记录", Snackbar.LENGTH_LONG)
                                .setAction("撤销") {
                                    HistoryStore.restore(ctx, e)
                                    render()
                                }
                                .show()
                        }
                        true
                    }
                }, dividerInsetDp = 52)
            }
            b.list.addView(group)
        }
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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
