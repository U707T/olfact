package cn.olfact.app.ui.browser

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import cn.olfact.app.R
import cn.olfact.app.data.AppSettings
import cn.olfact.app.data.BookmarkStore
import cn.olfact.app.data.BrowserSession
import cn.olfact.app.data.K
import cn.olfact.app.nav.Nav
import cn.olfact.app.ui.ai.AiActions
import cn.olfact.app.ui.download.AddDownloadSheet
import cn.olfact.core.uikit.ListRow
import cn.olfact.core.uikit.Sheet
import cn.olfact.core.uikit.dpF

/**
 * 长按链接/图片菜单。
 *
 * [showAt] 是原版形态：锚在手指位置的白色浮层（纯文字、行高约 52dp）。
 * [show] 保留底部 Sheet 作为兜底（没有坐标时，例如无障碍/非触摸触发）。
 */
object LongPressSheet {

    private data class Action(val label: String, val action: () -> Unit)

    /** 当前显示的浮层：重复长按时先收起旧的。 */
    private var currentPopup: PopupWindow? = null

    /** Activity 销毁时清掉静态引用。 */
    fun dismiss() {
        currentPopup?.takeIf { it.isShowing }?.dismiss()
        currentPopup = null
    }

    fun show(context: Context, url: String?, title: String?, onFind: (() -> Unit)? = null) {
        val target = url?.takeIf { it.isNotBlank() }
        val sheet = Sheet(context).title(title?.takeIf { it.isNotBlank() } ?: "页面操作")
        if (target != null) sheet.subtitle(target)
        actions(context, url, title, onFind).forEach { action ->
            sheet.addRow(ListRow(context).apply {
                this.title = action.label
                onRowClick = {
                    sheet.dismiss()
                    action.action()
                }
            })
        }
        sheet.show()
    }

    /** 锚定浮层：x/y 是 WebView 内的坐标，anchor 用来换算屏幕位置。 */
    fun showAt(
        context: Context,
        url: String?,
        title: String?,
        anchor: View,
        x: Float,
        y: Float,
        onFind: (() -> Unit)? = null
    ) {
        val items = actions(context, url, title, onFind)
        if (items.isEmpty()) return
        currentPopup?.takeIf { it.isShowing }?.dismiss()

        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = context.dpF(14f).toFloat()
                setColor(ContextCompat.getColor(context, R.color.card))
            }
            elevation = context.dpF(8f).toFloat()
            setPadding(0, context.dpF(4f), 0, context.dpF(4f))
        }
        var popup: PopupWindow? = null
        items.forEachIndexed { index, item ->
            if (index > 0) {
                list.addView(android.view.View(context).apply {
                    setBackgroundColor(ContextCompat.getColor(context, R.color.divider))
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, context.dpF(1f).coerceAtLeast(1)
                ).apply { marginStart = context.dpF(20f) })
            }
            list.addView(TextView(context).apply {
                text = item.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    context.dpF(20f), context.dpF(15f),
                    context.dpF(20f), context.dpF(15f)
                )
                isClickable = true
                setOnClickListener {
                    popup?.dismiss()
                    item.action()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val scroller = ScrollView(context).apply { addView(list) }
        val metrics = context.resources.displayMetrics
        val width = context.dpF(208f).toInt()

        // 先量内容高度：PopupWindow 不能用 WRAP_CONTENT，固定 62% 屏高会在内容少时
        // 留一大片透明区域拦截点击。取「内容高度」和「最大 62% 屏高」的较小值。
        list.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val maxHeight = (metrics.heightPixels * 0.62f).toInt()
        val height = list.measuredHeight.coerceIn(1, maxHeight)

        popup = PopupWindow(scroller, width, height, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            isFocusable = true
        }
        currentPopup = popup
        popup.setOnDismissListener { if (currentPopup === popup) currentPopup = null }

        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val screenX = (location[0] + x.toInt()).coerceIn(0, (metrics.widthPixels - width).coerceAtLeast(0))
        val screenY = (location[1] + y.toInt()).coerceIn(0, (metrics.heightPixels - height).coerceAtLeast(0))
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, screenX, screenY)
    }

    private fun actions(
        context: Context,
        url: String?,
        title: String?,
        onFind: (() -> Unit)?
    ): List<Action> {
        val target = url?.takeIf { it.isNotBlank() }
        val out = mutableListOf<Action>()
        if (target != null) {
            out.add(Action("在新标签页打开") {
                // 后台标签：不抢当前页，否则地址栏和 currentId 会错位。
                BrowserSession.open(target, selectNew = false)
                (context as? cn.olfact.app.MainActivity)?.refreshBottomBar()
                Nav.toast("已在新标签页打开")
            })
            out.add(Action("复制链接") { copy(context, target) })
            out.add(Action("下载") {
                AddDownloadSheet.show(context) { u, name ->
                    cn.olfact.core.api.OlfactServices.downloads?.enqueue(u, name)
                    Nav.toast("已加入下载")
                }
            })
            out.add(Action("加入书签") {
                if (BookmarkStore.contains(target)) {
                    Nav.toast("已经在书签里了")
                } else {
                    BookmarkStore.add(context, title ?: target, target)
                    Nav.toast("已加入书签")
                }
            })
            out.add(Action("分享") {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, target)
                }
                context.startActivity(Intent.createChooser(send, "分享链接"))
            })
        }
        val activity = context as? androidx.activity.ComponentActivity
        if (activity != null) {
            if (AppSettings.get(K.AI_SUMMARY)) {
                out.add(Action("AI 总结本页") { AiActions.summarize(activity) })
            }
            if (AppSettings.get(K.AI_TRANSLATE)) {
                out.add(Action("AI 翻译本页") { AiActions.translatePage(activity) })
            }
        }
        onFind?.let { find -> out.add(Action("页内查找") { find() }) }
        out.add(Action("刷新") { (Nav.current() as? BrowserFragment)?.reload() })
        return out
    }

    private fun copy(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("url", text))
        Nav.toast("已复制")
    }
}
