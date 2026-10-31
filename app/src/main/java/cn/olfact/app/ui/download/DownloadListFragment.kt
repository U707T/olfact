package cn.olfact.app.ui.download

import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import cn.olfact.app.R
import cn.olfact.app.databinding.FragmentDownloadListBinding
import cn.olfact.app.nav.ChromeAware
import cn.olfact.app.nav.Nav
import cn.olfact.core.api.DownloadState
import cn.olfact.core.api.DownloadTask
import cn.olfact.core.api.OlfactServices
import cn.olfact.core.uikit.EmptyView
import cn.olfact.core.uikit.ListRow
import cn.olfact.core.uikit.Sheet
import kotlinx.coroutines.launch

/**
 * 下载列表：已下载 / 下载中两档，右上角 ⋮ 是原版那个六项菜单。
 */
class DownloadListFragment : Fragment(R.layout.fragment_download_list), ChromeAware {

    override val showsBottomBar: Boolean = true
    override val barId: String = "downloads"

    private var binding: FragmentDownloadListBinding? = null
    private var showRunning = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentDownloadListBinding.bind(view)
        binding = b

        b.tabDone.setOnClickListener { showRunning = false; render() }
        b.tabRunning.setOnClickListener { showRunning = true; render() }
        b.more.setOnClickListener { showMenu() }
        b.newTask.setOnClickListener { AddDownloadSheet.show(requireContext()) { url, name -> enqueue(url, name) } }
        b.clearAll.setOnClickListener { clearCurrent() }

        b.empty.setAction("新增下载") { AddDownloadSheet.show(requireContext()) { url, name -> enqueue(url, name) } }

        OlfactServices.downloads?.changes?.let { flow ->
            viewLifecycleOwner.lifecycleScope.launch {
                flow.collect { render() }
            }
        }
        render()
    }

    private fun enqueue(url: String, name: String?) {
        if (url.isBlank()) return
        OlfactServices.downloads?.enqueue(url, name?.ifBlank { null })
        Nav.toast("已加入下载队列")
        showRunning = true
        render()
    }

    private fun clearCurrent() {
        val center = OlfactServices.downloads ?: return
        if (showRunning) {
            center.tasks().filter { it.state != DownloadState.DONE }.forEach { center.remove(it.id, false) }
        } else {
            center.clearFinished()
        }
        render()
    }

    private fun render() {
        val b = binding ?: return
        val ctx = requireContext()
        val all = OlfactServices.downloads?.tasks().orEmpty()
        val list = if (showRunning) all.filter { it.state != DownloadState.DONE } else all.filter { it.state == DownloadState.DONE }

        b.tabDone.setTextColor(ContextCompat.getColor(ctx, if (showRunning) R.color.text_secondary else R.color.brand))
        b.tabRunning.setTextColor(ContextCompat.getColor(ctx, if (showRunning) R.color.brand else R.color.text_secondary))

        b.list.removeAllViews()
        b.empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        if (list.isEmpty()) {
            b.empty.text = if (showRunning) "没有正在下载的任务" else getString(R.string.empty_downloads)
            return
        }

        list.forEachIndexed { index, task ->
            if (index > 0) b.list.addView(divider())
            b.list.addView(row(task))
        }
    }

    private fun row(task: DownloadTask): View {
        val ctx = requireContext()
        val row = ListRow(ctx).apply {
            title = task.name
            subtitle = subtitleOf(task)
            trailing = trailingOf(task)
            setIcon(R.drawable.ic_file)
            onRowClick = { Nav.push(DownloadDetailFragment.newInstance(task.id)) }
        }
        return row
    }

    private fun subtitleOf(task: DownloadTask): String = when (task.state) {
        DownloadState.DONE -> "已完成 · ${formatSize(task.totalBytes)}"
        DownloadState.RUNNING -> "${formatSize(task.doneBytes)} / ${formatSize(task.totalBytes)} · ${formatSize(task.speedBytesPerSec)}/s"
        DownloadState.PAUSED -> "已暂停 · ${task.progress}%"
        DownloadState.QUEUED -> "排队中"
        DownloadState.MERGING -> "合并分片中…"
        DownloadState.FAILED -> task.error ?: "失败"
    }

    private fun trailingOf(task: DownloadTask): String = when (task.state) {
        DownloadState.DONE -> ""
        DownloadState.FAILED -> "重试"
        else -> "${task.progress}%"
    }

    private fun divider() = View(requireContext()).apply {
        layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1
        ).apply { marginStart = 52 }
        setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.divider))
    }

    private fun showMenu() {
        val sheet = Sheet(requireContext()).title("下载列表")
        listOf("排序方式", "批量删除", "文件管理", "显示时间", "分类显示", "更多设置").forEach { label ->
            sheet.addRow(ListRow(requireContext()).apply {
                title = label
                onRowClick = {
                    sheet.dismiss()
                    when (label) {
                        "更多设置" -> Nav.push(cn.olfact.app.ui.settings.SettingsPageFragment.newInstance("download"))
                        "文件管理" -> Nav.toast("交给系统文件管理器（/Download）")
                        else -> Nav.toast("$label：下载模块接入后生效")
                    }
                }
            })
        }
        sheet.show()
    }

    companion object {
        fun formatSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB")
            var v = bytes.toDouble()
            var i = 0
            while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
            return String.format(java.util.Locale.getDefault(), "%.1f %s", v, units[i])
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
