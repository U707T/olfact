package cn.olfact.app.ui.player

import android.app.Activity
import android.widget.Button
import cn.olfact.app.R
import cn.olfact.app.data.AppSettings
import cn.olfact.app.data.BrowserSession
import cn.olfact.app.data.K
import cn.olfact.app.data.Tab
import cn.olfact.app.nav.Nav
import cn.olfact.core.api.MediaType
import cn.olfact.core.api.MediaItem
import cn.olfact.core.api.OlfactServices
import cn.olfact.core.uikit.ListRow
import cn.olfact.core.uikit.Sheet

/** Results belong to the current tab and page; each row has a separate download action. */
object SnifferSheet {
    private var current: Sheet? = null

    fun dismiss() {
        current?.dismiss()
        current = null
    }

    fun show(activity: Activity) {
        dismiss()
        val tab = BrowserSession.current()
        val enabled = AppSettings.get(K.SNIFF_ENABLED)
        val available = OlfactServices.snifferFactory != null
        val list = if (enabled && available && tab != null) SnifferFeed.forPage(tab.id, tab.url) else emptyList()

        val sheet = Sheet(activity).title("嗅探结果")
        current = sheet
        sheet.setOnDismissListener { if (current === sheet) current = null }
        when {
            !enabled -> sheet.subtitle("嗅探已关闭，可在「嗅探和播放器」设置中开启")
            !available -> sheet.subtitle("当前浏览引擎不支持资源嗅探")
            list.isEmpty() -> sheet.subtitle("当前页暂无结果。播放媒体或重新加载此页后再查看。")
        }
        if (tab != null && list.isNotEmpty()) {
            val rows = android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                list.forEach { item -> addView(row(activity, tab, item, sheet)) }
            }
            sheet.add(android.widget.ScrollView(activity).apply { addView(rows) })
        }
        sheet.show()
    }

    private fun row(activity: Activity, tab: Tab, item: MediaItem, sheet: Sheet): ListRow = ListRow(activity).apply {
        title = item.title?.ifBlank { null } ?: item.url.substringBefore('?').substringAfterLast('/')
        subtitle = "${kindLabel(item.type)} · ${item.url}"
        setIcon(if (item.type == MediaType.HLS) R.drawable.ic_play else R.drawable.ic_file)
        onRowClick = {
            sheet.dismiss()
            Nav.push(PlayerFragment.newInstance(item.url, item.title.orEmpty(), tab.id, item.pageUrl, tab.incognito))
        }
        val label = title
        addTrailing(Button(activity).apply {
            text = "下载"
            contentDescription = "下载 $label"
            setOnClickListener {
                val center = OlfactServices.downloads
                if (center == null) {
                    Nav.toast("下载模块尚未就绪")
                } else {
                    val request = mediaDownloadContext(item.pageUrl, "", item.headers, item.mimeType)
                    center.enqueue(item.url, item.title?.ifBlank { null }, request.pageUrl, request.referer,
                        request.userAgent, request.mimeType, request.headers)
                    sheet.dismiss()
                }
            }
        })
    }

    private fun kindLabel(kind: MediaType) = when (kind) {
        MediaType.HLS -> "HLS"
        MediaType.MP4 -> "MP4"
        MediaType.DASH -> "DASH"
        MediaType.AUDIO -> "音频"
        MediaType.OTHER -> "媒体"
    }
}
