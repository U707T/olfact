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
import cn.olfact.app.ui.common.Dialogs
import cn.olfact.core.api.DownloadState
import cn.olfact.core.api.DownloadTask
import cn.olfact.core.api.OlfactServices
import cn.olfact.core.uikit.EmptyView
import cn.olfact.core.uikit.ListRow
import cn.olfact.core.uikit.Sheet
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * 下载列表：已下载 / 下载中两档，右上角 ⋮ 是原版那个六项菜单。
 */
class DownloadListFragment : Fragment(R.layout.fragment_download_list), ChromeAware {

    override val showsBottomBar: Boolean = true
    override val barId: String = "downloads"

    private var binding: FragmentDownloadListBinding? = null
    private var adapter: DownloadAdapter? = null
    private var showRunning = false
    private var sortMode = SortMode.TIME
    private var showTime = true

    private enum class SortMode(val label: String) {
        TIME("按时间"),
        NAME("按名称"),
        SIZE("按大小")
    }

    @OptIn(FlowPreview::class)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentDownloadListBinding.bind(view)
        binding = b

        b.tabDone.setOnClickListener { showRunning = false; render() }
        b.tabRunning.setOnClickListener { showRunning = true; render() }
        b.more.setOnClickListener { showMenu() }
        b.newTask.setOnClickListener { AddDownloadSheet.show(requireContext()) { url, name -> enqueue(url, name) } }
        b.clearAll.setOnClickListener { clearCurrent() }

        b.empty.setAction("新增下载") { AddDownloadSheet.show(requireContext()) { url, name -> enqueue(url, name) } }

        val a = DownloadAdapter(
            onOpen = { Nav.push(DownloadDetailFragment.newInstance(it.id)) },
            subtitleOf = { subtitleOf(it) },
            trailingOf = { trailingOf(it) }
        )
        adapter = a
        b.list.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        b.list.adapter = a
        b.list.addItemDecoration(cn.olfact.core.uikit.InsetDivider(requireContext()))

        OlfactServices.downloads?.changes?.let { flow ->
            viewLifecycleOwner.lifecycleScope.launch {
                // 下载进度每秒可能推很多次；250ms 采样一次，避免整页 removeAllViews。
                flow.sample(250).collect { render() }
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
        val filtered = if (showRunning) all.filter { it.state != DownloadState.DONE } else all.filter { it.state == DownloadState.DONE }
        val list = when (sortMode) {
            SortMode.TIME -> filtered.sortedByDescending { it.createdAt }
            SortMode.NAME -> filtered.sortedBy { it.name.lowercase() }
            SortMode.SIZE -> filtered.sortedByDescending { it.totalBytes }
        }

        val a = adapter ?: return
        b.tabDone.setTextColor(ContextCompat.getColor(ctx, if (showRunning) R.color.text_secondary else R.color.text_accent))
        b.tabRunning.setTextColor(ContextCompat.getColor(ctx, if (showRunning) R.color.text_accent else R.color.text_secondary))
        // 选中项下方 2dp 绿色指示条（原版有，之前只有文字变色）
        val underline = ContextCompat.getDrawable(ctx, R.drawable.bg_tab_underline)
        b.tabDone.background = if (showRunning) null else underline
        b.tabRunning.background = if (showRunning) underline else null
        b.tabDone.setPadding(0, 0, dp(20), dp(5))
        b.tabRunning.setPadding(0, 0, 0, dp(5))

        b.empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        if (list.isEmpty()) {
            b.empty.text = if (showRunning) "没有正在下载的任务" else getString(R.string.empty_downloads)
            a.submit(emptyList())
            return
        }

        a.submit(list)
    }

    private fun subtitleOf(task: DownloadTask): String {
        val base = when (task.state) {
            DownloadState.DONE -> "已完成 · ${formatSize(task.totalBytes)}"
            DownloadState.RUNNING -> "${formatSize(task.doneBytes)} / ${formatSize(task.totalBytes)} · ${formatSize(task.speedBytesPerSec)}/s"
            DownloadState.PAUSED -> "已暂停 · ${task.progress}%"
            DownloadState.QUEUED -> "排队中"
            DownloadState.MERGING -> "合并分片中…"
            DownloadState.FAILED -> task.error ?: "失败"
        }
        if (!showTime) return base
        val at = if (task.finishedAt > 0) task.finishedAt else task.createdAt
        return "$base · ${timeFormat.format(java.util.Date(at))}"
    }

    private fun trailingOf(task: DownloadTask): String = when (task.state) {
        DownloadState.DONE -> ""
        DownloadState.FAILED -> "重试"
        else -> "${task.progress}%"
    }

    private fun showMenu() {
        val sheet = Sheet(requireContext()).title("下载列表")
        listOf("排序方式", "显示时间", "全部暂停", "全部继续", "更多设置").forEach { label ->
            sheet.addRow(ListRow(requireContext()).apply {
                title = label
                when (label) {
                    "排序方式" -> trailing = sortMode.label
                    "显示时间" -> trailing = if (showTime) "开" else "关"
                }
                onRowClick = {
                    sheet.dismiss()
                    when (label) {
                        "排序方式" -> Dialogs.choose(
                            requireContext(),
                            "排序方式",
                            SortMode.entries.map { it.label },
                            sortMode.ordinal
                        ) { picked ->
                            sortMode = SortMode.entries[picked]
                            render()
                        }
                        "显示时间" -> {
                            showTime = !showTime
                            render()
                        }
                        "全部暂停" -> {
                            val center = OlfactServices.downloads
                            if (center != null) {
                                center.tasks()
                                    .filter { it.state == DownloadState.RUNNING || it.state == DownloadState.QUEUED }
                                    .forEach { center.pause(it.id) }
                                Nav.toast("已暂停全部任务")
                                render()
                            }
                        }
                        "全部继续" -> {
                            val center = OlfactServices.downloads
                            if (center != null) {
                                center.tasks()
                                    .filter { it.state == DownloadState.PAUSED || it.state == DownloadState.FAILED }
                                    .forEach { center.resume(it.id) }
                                Nav.toast("已继续全部任务")
                                render()
                            }
                        }
                        "更多设置" -> Nav.push(cn.olfact.app.ui.settings.SettingsPageFragment.newInstance("download"))
                    }
                }
            })
        }
        sheet.show()
    }

    companion object {
        private val timeFormat = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())

        fun formatSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB")
            var v = bytes.toDouble()
            var i = 0
            while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
            return String.format(java.util.Locale.getDefault(), "%.1f %s", v, units[i])
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
