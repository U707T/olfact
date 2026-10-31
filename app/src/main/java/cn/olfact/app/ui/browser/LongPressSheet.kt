package cn.olfact.app.ui.browser

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import cn.olfact.app.R
import cn.olfact.app.data.BookmarkStore
import cn.olfact.app.data.BrowserSession
import cn.olfact.app.nav.Nav
import cn.olfact.app.ui.ai.AiActions
import cn.olfact.app.ui.download.AddDownloadSheet
import cn.olfact.core.uikit.ListRow
import cn.olfact.core.uikit.Sheet

/** 长按链接/图片菜单：新标签打开、复制、下载、书签、分享。 */
object LongPressSheet {

    fun show(context: Context, url: String?, title: String?, onFind: (() -> Unit)? = null) {
        val target = url?.takeIf { it.isNotBlank() }
        val sheet = Sheet(context).title(title?.takeIf { it.isNotBlank() } ?: "页面操作")
        if (target != null) sheet.subtitle(target)

        fun row(label: String, icon: Int, rawIcon: Boolean = false, action: () -> Unit) {
            sheet.addRow(ListRow(context).apply {
                this.title = label
                if (rawIcon) setIconOriginal(icon) else setIcon(icon)
                onRowClick = {
                    sheet.dismiss()
                    action()
                }
            })
        }

        if (target != null) {
            row("在新标签页打开", R.drawable.ic_tabs) {
                BrowserSession.open(target)
                Nav.toast("已在新标签页打开")
            }
            row("复制链接", R.drawable.ic_copy) {
                copy(context, target)
            }
            row("下载", R.drawable.orig_m_download) {
                AddDownloadSheet.show(context) { url, name ->
                    cn.olfact.core.api.OlfactServices.downloads?.enqueue(url, name)
                    Nav.toast("已加入下载")
                }
            }
            row("加入书签", R.drawable.orig_m_bookmark_add) {
                BookmarkStore.add(context, title ?: target, target)
                Nav.toast("已加入书签")
            }
            row("分享", R.drawable.ic_share) {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, target)
                }
                context.startActivity(Intent.createChooser(send, "分享链接"))
            }
        }
        val activity = context as? androidx.activity.ComponentActivity
        if (activity != null) {
            row("AI 总结本页", R.drawable.orig_m_ai, rawIcon = true) { AiActions.summarize(activity) }
            row("AI 翻译本页", R.drawable.orig_m_ai_alt, rawIcon = true) { AiActions.translatePage(activity) }
        }
        onFind?.let { find ->
            row("页内查找", R.drawable.ic_search) { find() }
        }
        row("刷新", R.drawable.orig_m_refresh) {
            (Nav.current() as? BrowserFragment)?.reload()
        }
        sheet.show()
    }

    private fun copy(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("url", text))
        Nav.toast("已复制")
    }
}
