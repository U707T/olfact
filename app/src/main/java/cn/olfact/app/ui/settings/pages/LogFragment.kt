package cn.olfact.app.ui.settings.pages

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import cn.olfact.app.R
import cn.olfact.app.data.AppLog
import cn.olfact.app.databinding.FragmentPageBinding
import cn.olfact.app.nav.Nav
import cn.olfact.core.uikit.dpF

/** 日志页：读 [AppLog] 的环形缓冲，支持按级别过滤 / 清空 / 复制。 */
class LogFragment : Fragment(R.layout.fragment_page) {

    private var binding: FragmentPageBinding? = null
    private var minLevel = AppLog.Level.DEBUG
    private val listener: () -> Unit = { render() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentPageBinding.bind(view)
        binding = b
        b.header.title = "日志"
        b.header.onBack = { Nav.pop() }
        b.header.addAction(R.drawable.ic_trash, "清空") {
            AppLog.clear()
            render()
        }
        AppLog.observe(listener)
        render()
    }

    private fun render() {
        val b = binding ?: return
        val ctx = requireContext()
        b.content.removeAllViews()

        val filters = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "全部" to AppLog.Level.DEBUG,
            "警告" to AppLog.Level.WARN,
            "错误" to AppLog.Level.ERROR
        ).forEach { (label, level) ->
            filters.addView(TextView(ctx).apply {
                text = label
                setPadding(ctx.dpF(12f), ctx.dpF(6f), ctx.dpF(12f), ctx.dpF(6f))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(ContextCompat.getColor(ctx, if (level == minLevel) R.color.white else R.color.text_primary))
                setBackgroundResource(if (level == minLevel) R.drawable.bg_chip_dark else R.drawable.bg_button_ghost)
                setOnClickListener { minLevel = level; render() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, ctx.dpF(32f)).apply { marginEnd = ctx.dpF(8f) })
        }
        b.content.addView(filters)

        val list = AppLog.all().filter {
            when (minLevel) {
                AppLog.Level.DEBUG -> true
                AppLog.Level.INFO -> it.level != AppLog.Level.DEBUG
                AppLog.Level.WARN -> it.level == AppLog.Level.WARN || it.level == AppLog.Level.ERROR
                AppLog.Level.ERROR -> it.level == AppLog.Level.ERROR
            }
        }

        if (list.isEmpty()) {
            b.content.addView(TextView(ctx).apply {
                text = "暂无日志。浏览、下载、脚本操作会写到这里。"
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, requireContext().dpF(24f), 0, 0)
            })
            return
        }

        list.forEach { e ->
            b.content.addView(TextView(ctx).apply {
                text = "${e.time()}  ${e.level.name.first()}  [${e.tag}]  ${e.message}"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(ContextCompat.getColor(ctx, when (e.level) {
                    AppLog.Level.ERROR -> R.color.danger
                    AppLog.Level.WARN -> R.color.text_primary
                    else -> R.color.text_secondary
                }))
                setPadding(0, requireContext().dpF(8f), 0, 0)
                setTextIsSelectable(true)
            })
        }
    }

    override fun onDestroyView() {
        AppLog.unobserve(listener)
        binding = null
        super.onDestroyView()
    }
}
