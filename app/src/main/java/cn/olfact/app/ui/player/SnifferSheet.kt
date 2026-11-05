package cn.olfact.app.ui.player

import android.app.Activity
import androidx.core.content.ContextCompat
import cn.olfact.app.R
import cn.olfact.app.data.BrowserSession
import cn.olfact.app.nav.Nav
import cn.olfact.app.ui.download.DownloadListFragment
import cn.olfact.core.api.MediaType
import cn.olfact.core.api.MediaItem
import cn.olfact.core.api.OlfactServices
import cn.olfact.core.uikit.ListRow
import cn.olfact.core.uikit.Sheet

/** 嗅探结果列表：每个媒体一条，点开进播放页，右侧可下载。 */
object SnifferSheet {

    /** 当前显示的嗅探面板：重复点「嗅探结果」先收起旧的，避免叠层。 */
    private var current: Sheet? = null

    /** Activity 销毁时清掉静态引用。 */
    fun dismiss() {
        current?.takeIf { it.isShowing }?.dismiss()
        current = null
    }

    fun show(activity: Activity) {
        current?.takeIf { it.isShowing }?.dismiss()
        val pageUrl = BrowserSession.current()?.url.orEmpty()
        val real = SnifferFeed.forPage(pageUrl).ifEmpty { SnifferFeed.items }
        val list = if (real.isEmpty() && OlfactServices.sniffer == null) SnifferFeed.demo() else real
        val isDemo = real.isEmpty() && list.isNotEmpty()

        val sheet = Sheet(activity).title("嗅探结果")
        current = sheet
        sheet.setOnDismissListener { if (current === sheet) current = null }
        if (isDemo) sheet.subtitle("示例数据 · 尚未检测到真实媒体")
        if (list.isEmpty()) {
            sheet.subtitle("当前页面还没有嗅探到媒体，播放视频后会自动出现")
        }

        list.forEach { item -> sheet.addRow(row(activity, item, sheet)) }
        sheet.show()
    }

    private fun row(activity: Activity, item: MediaItem, sheet: Sheet): ListRow {
        return ListRow(activity).apply {
            title = item.title?.ifBlank { null } ?: item.url.substringAfterLast('/')
            subtitle = "${kindLabel(item.type)} · ${item.url}"
            setIcon(if (item.type == MediaType.HLS) R.drawable.ic_play else R.drawable.ic_file)
            trailing = "下载"
            onRowClick = {
                sheet.dismiss()
                // 先入 SnifferFeed 再开播放页：PlayerFragment 会按 URL 取回这条记录里的
                // Referer / Cookie，丢掉就会 403。
                SnifferFeed.add(item)
                Nav.push(PlayerFragment.newInstance(item.url, item.title.orEmpty()))
            }
            // 长按直接下载：把嗅探到的请求头（Referer / Cookie / UA）一并带下去，
            // 否则防盗链站点的直链只能下到一个 403 页面。
            setOnLongClickListener {
                sheet.dismiss()
                OlfactServices.downloads?.enqueue(
                    url = item.url,
                    name = item.title?.ifBlank { null },
                    pageUrl = item.pageUrl.orEmpty(),
                    referer = item.pageUrl.orEmpty(),
                    userAgent = item.headers.entries
                        .firstOrNull { it.key.equals("User-Agent", true) }?.value,
                    mimeType = item.mimeType,
                    headers = item.headers
                )
                Nav.toast("已加入下载")
                true
            }
        }
    }

    private fun kindLabel(kind: MediaType) = when (kind) {
        MediaType.HLS -> "HLS"
        MediaType.MP4 -> "MP4"
        MediaType.DASH -> "DASH"
        MediaType.AUDIO -> "音频"
        MediaType.OTHER -> "媒体"
    }
}
