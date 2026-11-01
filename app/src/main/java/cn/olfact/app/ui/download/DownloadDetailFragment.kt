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
        b.actionPrimary.text = when (task.state) {
            DownloadState.RUNNING -> "暂停"
            DownloadState.PAUSED, DownloadState.FAILED -> "继续"
            DownloadState.DONE -> "打开文件"
            else -> "等待调度"
        }
        // 已完成时主按钮就是「打开文件」，不再重复显示第二个打开按钮
        b.actionOpen.visibility = View.GONE
        b.progress.progress = task.progress
        b.size.text = "${DownloadListFragment.formatSize(task.doneBytes)} / ${DownloadListFragment.formatSize(task.totalBytes)}"
        b.url.text = task.url
        b.url.setOnClickListener { copyText("下载地址", task.url) }
        b.path.text = task.savePath.ifBlank { "默认下载目录" }
        b.path.setOnClickListener { copyText("文件路径", task.savePath) }

        b.actionPrimary.setOnClickListener {
            when (task.state) {
                DownloadState.RUNNING -> {
                    center?.pause(task.id)
                    Nav.toast("已暂停")
                    Nav.pop()
                }
                DownloadState.PAUSED, DownloadState.FAILED -> {
                    center?.resume(task.id)
                    Nav.toast("继续下载")
                    Nav.pop()
                }
                // 已完成时主按钮直接打开文件，不再只弹「已下载完成」
                DownloadState.DONE -> openFile(task.savePath)
                else -> {
                    Nav.toast("等待调度")
                    Nav.pop()
                }
            }
        }
        b.actionOpen.setOnClickListener {
            if (task.state != DownloadState.DONE) {
                Nav.toast("还没下完")
                return@setOnClickListener
            }
            openFile(task.savePath)
        }
        b.actionDelete.setOnClickListener {
            // 删除任务会连带删文件，必须先确认
            cn.olfact.app.ui.common.Dialogs.confirm(
                requireContext(),
                "删除下载",
                "将删除任务记录和已下载的文件，不可恢复。继续？",
                danger = true
            ) {
                center?.remove(task.id, true)
                Nav.toast("已删除")
                Nav.pop()
            }
        }
    }

    private fun copyText(label: String, text: String) {
        if (text.isBlank()) {
            Nav.toast("没有可复制的$label")
            return
        }
        val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        Nav.toast("已复制$label")
    }

    /** 已下载文件：content:// 直接打开；绝对路径/公共 Downloads 走 FileProvider 分享。 */
    private fun openFile(path: String) {
        if (path.isBlank()) {
            Nav.toast("文件路径未知")
            return
        }
        val ctx = requireContext()
        val uri = if (path.startsWith("content://") || path.startsWith("file://")) {
            android.net.Uri.parse(path)
        } else {
            val file = if (path.startsWith("/")) {
                java.io.File(path)
            } else {
                java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                    path
                )
            }
            if (!file.exists()) {
                Nav.toast("文件不存在：${file.absolutePath}")
                return
            }
            runCatching {
                androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            }.getOrElse {
                Nav.toast("无法分享文件：${it.message}")
                return
            }
        }
        val ext = android.net.Uri.parse(path).lastPathSegment?.substringAfterLast('.')?.lowercase().orEmpty()
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        runCatching {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.onFailure { Nav.toast("没有能打开该文件的应用") }
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
