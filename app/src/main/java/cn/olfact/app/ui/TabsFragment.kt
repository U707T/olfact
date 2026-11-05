package cn.olfact.app.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import cn.olfact.app.R
import cn.olfact.app.data.BrowserSession
import cn.olfact.app.data.Tab
import cn.olfact.app.databinding.FragmentTabsBinding
import cn.olfact.app.nav.Nav
import cn.olfact.app.ui.browser.BrowserFragment
import cn.olfact.app.ui.common.Dialogs
import cn.olfact.core.uikit.dpF

/**
 * 多窗口列表：对齐原版 `pop_multi_window_text.xml` 的覆盖层。
 *
 * - 覆盖层自带底部三个动作（清空 / 新增 / 完成），因此**不显示主底栏**；
 *   否则会出现「底栏的标签数」和「覆盖层」两套标签页入口，与原版不符。
 * - 选中标签只 pop 回浏览器并换绑标签（[Nav.switchToTab]），不再 `Nav.root`，
 *   避免每次切标签都把首页与返回栈重建一遍。
 */
class TabsFragment : Fragment(R.layout.fragment_tabs) {

    private var binding: FragmentTabsBinding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentTabsBinding.bind(view)
        binding = b

        b.clearAll.setOnClickListener {
            val count = BrowserSession.tabs.size
            Dialogs.confirm(
                requireContext(), "关闭全部标签", "将关闭 $count 个标签页。",
                danger = true, positiveLabel = "关闭"
            ) {
                BrowserSession.closeAll()
                render()
            }
        }
        b.newTab.setOnClickListener { openTab(BrowserSession.openBlank(reuseBlank = false).id) }
        b.done.setOnClickListener { closeOverlay() }

        // 系统返回 = 收起覆盖层，而不是退出应用。
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    closeOverlay()
                }
            }
        )

        render()
    }

    /** 底栏/菜单调用的刷新入口（覆盖层现在不挂底栏，保留给外部调用）。 */
    fun refresh() {
        render()
    }

    /** 选中或新建标签：能复用底下的浏览器就复用，否则 push 一层浏览器（保留返回栈）。 */
    private fun openTab(tabId: Long) {
        BrowserSession.select(tabId)
        if (!Nav.switchToTab(tabId)) {
            val blank = BrowserSession.tabs.firstOrNull { it.id == tabId }?.url.isNullOrBlank()
            Nav.push(BrowserFragment.newInstance(tabId, focusUrl = blank))
        }
    }

    /**
     * 完成 / 返回：只收起覆盖层，不额外打开页面。
     *
     * 从浏览器进来时，pop 之后底下的 BrowserFragment 仍是刚才那个标签；
     * 从首页进来时，pop 之后回到首页——与原版「多窗口是覆盖层」的行为一致。
     */
    private fun closeOverlay() {
        if (Nav.current() is TabsFragment) Nav.pop()
    }

    private fun render() {
        val b = binding ?: return
        val ctx = requireContext()
        b.countText.text = "共 ${BrowserSession.tabs.size} 个"
        b.tabList.removeAllViews()

        BrowserSession.tabs.forEach { tab ->
            b.tabList.addView(
                buildRow(tab),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = ctx.dpF(8f) }
            )
        }

        // 会话至少保留一个空白页；「恢复上次标签」仍然要在只有空白页时可见。
        val onlyBlank = BrowserSession.tabs.size <= 1 && BrowserSession.current()?.url.isNullOrBlank()
        val saved = BrowserSession.savedCount(ctx)
        if (onlyBlank && saved > 0) {
            b.tabList.addView(TextView(ctx).apply {
                text = "恢复上次的 $saved 个标签"
                gravity = Gravity.CENTER
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(ContextCompat.getColor(ctx, R.color.text_accent))
                setPadding(0, ctx.dpF(10f), 0, ctx.dpF(10f))
                setOnClickListener {
                    BrowserSession.restore(ctx)
                    render()
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    private fun buildRow(tab: Tab): View {
        val ctx = requireContext()
        val selected = tab.id == BrowserSession.currentId

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.dpF(52f)
            setPadding(ctx.dpF(10f), ctx.dpF(4f), ctx.dpF(4f), ctx.dpF(4f))
            background = ContextCompat.getDrawable(
                ctx,
                if (selected) R.drawable.bg_tab_card_selected else R.drawable.bg_tab_card
            )
            isClickable = true
            setOnClickListener { openTab(tab.id) }
        }

        row.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.orig_tab_globe)
            contentDescription = null
            setPadding(ctx.dpF(6f), ctx.dpF(6f), ctx.dpF(6f), ctx.dpF(6f))
        }, LinearLayout.LayoutParams(ctx.dpF(32f), ctx.dpF(32f)))

        row.addView(TextView(ctx).apply {
            text = tab.title.ifBlank { "新标签页" }
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(
                ContextCompat.getColor(ctx, if (selected) R.color.text_accent else R.color.text_primary)
            )
            if (selected) setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(ctx.dpF(4f), 0, ctx.dpF(4f), 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        row.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.orig_tab_close)
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
