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
import cn.olfact.core.uikit.dpF

/** 标签页管理：两列卡片，点选切换、右上角关闭。 */
class TabsFragment : Fragment(R.layout.fragment_tabs), ChromeAware {

    override val showsBottomBar: Boolean = true
    override val barId: String = "tabs"

    private var binding: FragmentTabsBinding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentTabsBinding.bind(view)
        binding = b
        b.header.title = "标签页"
        b.header.onBack = { Nav.pop() }
        b.newTab.setOnClickListener {
            val tab = BrowserSession.open(null)
            Nav.push(BrowserFragment.newInstance(tab.id))
        }
        b.closeAll.setOnClickListener {
            BrowserSession.closeAll()
            render()
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

        BrowserSession.tabs.chunked(2).forEach { pair ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, ctx.dpF(10f))
            }
            pair.forEach { tab ->
                row.addView(buildCard(tab), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = ctx.dpF(10f)
                })
            }
            if (pair.size == 1) {
                row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f).apply { marginEnd = ctx.dpF(10f) })
            }
            b.tabList.addView(row)
        }
    }

    private fun buildCard(tab: Tab): View {
        val ctx = requireContext()
        val selected = tab.id == BrowserSession.currentId
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_tab_card)
            setPadding(ctx.dpF(14f), ctx.dpF(12f), ctx.dpF(10f), ctx.dpF(12f))
            isClickable = true
        }
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(ctx).apply {
            text = tab.title.ifBlank { "新标签页" }
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(ContextCompat.getColor(ctx, if (selected) R.color.text_accent else R.color.text_primary))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = ContextCompat.getColorStateList(ctx, R.color.text_hint)
            // 40dp 热区（原来 26dp，低于 48dp 建议值的一半）
            setPadding(ctx.dpF(12f), ctx.dpF(12f), ctx.dpF(12f), ctx.dpF(12f))
            setOnClickListener {
                BrowserSession.close(tab.id)
                render()
            }
        }, LinearLayout.LayoutParams(ctx.dpF(40f), ctx.dpF(40f)))
        card.addView(top)
        card.addView(TextView(ctx).apply {
            text = tab.url.ifBlank { "about:blank" }
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, ctx.dpF(6f), 0, 0)
        })
        card.setOnClickListener {
            val sameTab = BrowserSession.currentId == tab.id
            BrowserSession.select(tab.id)
            // 点当前标签 = 回到浏览器，不再 push 一个重复的 BrowserFragment
            if (sameTab) Nav.pop() else Nav.push(BrowserFragment.newInstance(tab.id))
        }
        return card
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
