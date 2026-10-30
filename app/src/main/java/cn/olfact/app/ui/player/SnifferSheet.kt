package cn.olfact.app.ui.player

import android.app.Activity
import androidx.core.content.ContextCompat
import cn.olfact.app.R
import cn.olfact.app.data.BrowserSession
import cn.olfact.app.nav.Nav
import cn.olfact.app.ui.download.DownloadListFragment
import cn.olfact.core.api.MediaKind
import cn.olfact.core.api.MediaItem
import cn.olfact.core.api.OlfactServices
import cn.olfact.core.uikit.ListRow
import cn.olfact.core.uikit.Sheet

/** 嗅探结果列表：每个媒体一条，点开进播放页，右侧可下载。 */
object SnifferSheet {

    fun show(activity: Activity) {
        val pageUrl = BrowserSession.current()?.url.orEmpty()
        val real = SnifferFeed.forPage(pageUrl).ifEmpty { SnifferFeed.items }
        val list = if (real.isEmpty() && OlfactServices.sniffer == null) SnifferFeed.demo() else real
        val isDemo = real.isEmpty() && list.isNotEmpty()

        val sheet = Sheet(activity).title("嗅探结果")
        if (isDemo) sheet.subtitle("示例数据 · web 模块接入后来自真实请求")
        if (list.isEmpty()) {
            sheet.subtitle("当前页面还没有嗅探到媒体，播放视频后会自动出现")
        }

        list.forEach { item -> sheet.addRow(row(activity, item, sheet)) }
        sheet.show()
    }

    private fun row(activity: Activity, item: MediaItem, sheet: Sheet): ListRow {
        return ListRow(activity).apply {
            title = item.title.ifBlank { item.url.substringAfterLast('/') }
            subtitle = "${kindLabel(item.kind)} · ${item.url}"
            setIcon(if (item.kind == MediaKind.HLS) R.drawable.ic_play else R.drawable.ic_file)
            trailing = "下载"
            onRowClick = {
                sheet.dismiss()
                Nav.push(PlayerFragment.newInstance(item.url, item.title))
                SnifferFeed.add(item)
            }
            // 长按直接下载
            setOnLongClickListener {
                sheet.dismiss()
                OlfactServices.downloads?.enqueue(item.url, item.title.ifBlank { null }, item.pageUrl)
                Nav.toast("已加入下载")
                true
            }
        }
    }

    private fun kindLabel(kind: MediaKind) = when (kind) {
        MediaKind.HLS -> "HLS"
        MediaKind.MP4 -> "MP4"
        MediaKind.DASH -> "DASH"
        MediaKind.AUDIO -> "音频"
        MediaKind.UNKNOWN -> "媒体"
    }
}
