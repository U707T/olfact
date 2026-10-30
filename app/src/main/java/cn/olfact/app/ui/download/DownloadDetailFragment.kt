package cn.olfact.app.ui.download

import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import cn.olfact.app.R
import cn.olfact.app.databinding.FragmentDownloadDetailBinding
import cn.olfact.app.nav.Nav
import cn.olfact.core.api.DownloadState
import cn.olfact.core.api.OlfactServices
import cn.olfact.core.uikit.ListRow
import cn.olfact.core.uikit.SettingGroup

/** 下载详情：文件信息 + 三个动作（暂停/继续、打开、删除）。 */
class DownloadDetailFragment : Fragment(R.layout.fragment_download_detail) {

    private var binding: FragmentDownloadDetailBinding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentDownloadDetailBinding.bind(view)
        binding = b
        b.header.title = "下载详情"
        b.header.onBack = { Nav.pop() }

        val id = requireArguments().getString(ARG_ID).orEmpty()
        val center = OlfactServices.downloads
        val task = center?.tasks()?.firstOrNull { it.id == id }
        if (task == null) {
            b.name.text = "任务已删除"
            return
        }

        b.name.text = task.name
        b.state.text = stateText(task)
        b.progress.progress = task.progress
        b.size.text = "${DownloadListFragment.formatSize(task.doneBytes)} / ${DownloadListFragment.formatSize(task.totalBytes)}"
        b.url.text = task.url
        b.path.text = task.savePath.ifBlank { "默认下载目录" }

        b.actionPrimary.setOnClickListener {
            when (task.state) {
                DownloadState.RUNNING -> { center?.pause(task.id); Nav.toast("已暂停") }
                DownloadState.PAUSED, DownloadState.FAILED -> { center?.resume(task.id); Nav.toast("继续下载") }
                DownloadState.DONE -> Nav.toast("已下载完成")
                else -> Nav.toast("等待调度")
            }
            Nav.pop()
        }
        b.actionOpen.setOnClickListener {
            Nav.toast(if (task.state == DownloadState.DONE) "打开：${task.savePath}" else "还没下完")
        }
        b.actionDelete.setOnClickListener {
            center?.remove(task.id, true)
            Nav.toast("已删除")
            Nav.pop()
        }
    }

    private fun stateText(task: cn.olfact.core.api.DownloadTask): String = when (task.state) {
        DownloadState.DONE -> "已完成"
        DownloadState.RUNNING -> "下载中 ${DownloadListFragment.formatSize(task.speedBytesPerSec)}/s"
        DownloadState.PAUSED -> "已暂停"
        DownloadState.QUEUED -> "排队中"
        DownloadState.MERGING -> "合并分片中"
        DownloadState.FAILED -> task.error ?: "失败"
    }

    companion object {
        private const val ARG_ID = "id"
        fun newInstance(id: String) = DownloadDetailFragment().apply {
            arguments = Bundle().apply { putString(ARG_ID, id) }
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
