package cn.olfact.app.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import cn.olfact.app.R
import cn.olfact.app.data.BrowserSession
import cn.olfact.app.data.Tab
import cn.olfact.app.databinding.FragmentTabsBinding
import cn.olfact.app.nav.ChromeAware
import cn.olfact.app.nav.Nav
import cn.olfact.app.ui.browser.BrowserFragment
import cn.olfact.app.ui.common.Dialogs
import cn.olfact.core.uikit.dpF

/**
 * 标签页管理：单列列表 + 当前项高亮 + 底部新建按钮。
 *
 * 点任意标签都走 `Nav.root`：标签页是浏览器的子页，切换后不应该在返回栈里
 * 再叠一层浏览器；再次点底栏「标签」则会收起（见 MainActivity 的 toggle）。
 */
class TabsFragment : Fragment(R.layout.fragment_tabs), ChromeAware {

    override val showsBottomBar: Boolean = true
    override val barId: String = "tabs"

    private var binding: FragmentTabsBinding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentTabsBinding.bind(view)
        binding = b
        b.header.title = "标签页"
        b.header.onBack = { Nav.pop() }
        b.header.addAction(R.drawable.ic_trash, "关闭全部") {
            val count = BrowserSession.tabs.size
            if (count == 0) return@addAction
            Dialogs.confirm(requireContext(), "关闭全部标签", "将关闭 $count 个标签页。", danger = true) {
                BrowserSession.closeAll()
                render()
            }
        }
        b.newTab.setOnClickListener {
            val tab = BrowserSession.open(null)
            Nav.root(BrowserFragment.newInstance(tab.id))
        }
        render()
    }

    private fun render() {
        val b = binding ?: return
        val ctx = requireContext()
        b.countText.text = "共 ${BrowserSession.tabs.size} 个标签页"
        b.tabList.removeAllViews()

        if (BrowserSession.tabs.isEmpty()) {
            b.tabList.addView(TextView(ctx).apply {
                text = "没有打开的标签页"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                gravity = Gravity.CENTER
                setPadding(0, ctx.dpF(48f), 0, ctx.dpF(12f))
            })
            val saved = BrowserSession.savedCount(ctx)
            if (saved > 0) {
                b.tabList.addView(TextView(ctx).apply {
                    text = "恢复上次的 $saved 个标签"
                    gravity = Gravity.CENTER
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_accent))
                    setPadding(0, ctx.dpF(8f), 0, ctx.dpF(8f))
                    setOnClickListener {
                        BrowserSession.restore(ctx)
                        render()
                    }
                })
            }
            return
        }

        BrowserSession.tabs.forEach { tab ->
            b.tabList.addView(buildRow(tab), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = ctx.dpF(6f) })
        }
    }

    private fun buildRow(tab: Tab): View {
        val ctx = requireContext()
        val selected = tab.id == BrowserSession.currentId

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dpF(12f), ctx.dpF(12f), ctx.dpF(6f), ctx.dpF(12f))
            background = if (selected) {
                ContextCompat.getDrawable(ctx, R.drawable.bg_tab_selected)
            } else {
                ContextCompat.getDrawable(ctx, cn.olfact.core.R.drawable.uikit_ripple_row)
            }
            isClickable = true
            setOnClickListener {
                // 点当前标签：如果是从浏览器进来的，直接 pop 回去（保留滚动/表单）
                if (tab.id == BrowserSession.currentId && Nav.popIfPreviousIs(BrowserFragment::class.java)) {
                    return@setOnClickListener
                }
                // 其它情况：切换标签 = 重设浏览器根，避免返回栈越点越深
                BrowserSession.select(tab.id)
                Nav.root(BrowserFragment.newInstance(tab.id))
            }
        }

        // 选中指示条
        row.addView(View(ctx).apply {
            setBackgroundColor(
                ContextCompat.getColor(ctx, if (selected) R.color.brand else android.R.color.transparent)
            )
        }, LinearLayout.LayoutParams(ctx.dpF(3f), ctx.dpF(36f)).apply { marginEnd = ctx.dpF(12f) })

        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(ctx).apply {
            text = tab.title.ifBlank { "新标签页" }
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(ContextCompat.getColor(ctx, if (selected) R.color.text_accent else R.color.text_primary))
            if (selected) setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        texts.addView(TextView(ctx).apply {
            text = tab.url.ifBlank { "about:blank" }
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, ctx.dpF(4f), 0, 0)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        row.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = ContextCompat.getColorStateList(ctx, R.color.text_hint)
            contentDescription = "关闭标签"
            setPadding(ctx.dpF(12f), ctx.dpF(12f), ctx.dpF(12f), ctx.dpF(12f))
            setOnClickListener {
                BrowserSession.close(tab.id)
                render()
            }
        }, LinearLayout.LayoutParams(ctx.dpF(40f), ctx.dpF(40f)))

        return row
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
