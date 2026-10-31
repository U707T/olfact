package cn.olfact.app.ui.settings

import android.os.Bundle
import android.view.View
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import cn.olfact.app.R
import cn.olfact.app.databinding.FragmentSettingsBinding
import cn.olfact.app.nav.Nav
import cn.olfact.app.ui.settings.pages.AboutFragment
import cn.olfact.app.ui.settings.pages.AdBlockFragment
import cn.olfact.app.ui.settings.pages.AiFragment
import cn.olfact.app.ui.settings.pages.BackupFragment
import cn.olfact.app.ui.settings.pages.DohFragment
import cn.olfact.app.ui.settings.pages.ScriptFragment
import cn.olfact.core.uikit.SettingGroup
import cn.olfact.core.uikit.SettingItem

/**
 * 设置首页：分组卡片 + 图标行，结构与 docs/refs/08-设置页分区.jpg 一致。
 * 顶部搜索框只过滤入口标题（不索引子页里的具体开关）。
 */
class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private var binding: FragmentSettingsBinding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentSettingsBinding.bind(view)
        binding = b
        b.header.title = "设置"
        b.header.onBack = { Nav.pop() }
        b.search.doOnTextChanged { text, _, _, _ -> render(text?.toString().orEmpty()) }
        b.search.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                val imm = requireContext().getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                imm?.hideSoftInputFromWindow(v.windowToken, 0)
                v.clearFocus()
                true
            } else {
                false
            }
        }
        render("")
    }

    private fun render(query: String) {
        val b = binding ?: return
        b.sections.removeAllViews()
        var first = true
        var added = 0
        groups().forEach { rows ->
            val filtered = if (query.isBlank()) rows else rows.filter { it.title.contains(query, ignoreCase = true) }
            if (filtered.isEmpty()) return@forEach
            val group = SettingGroup(requireContext())
            filtered.forEach { row ->
                val item = SettingItem(requireContext()).apply {
                    title = row.title
                    if (row.rawIcon) setIconOriginal(row.icon) else setIcon(row.icon)
                    mode = SettingItem.Mode.CHEVRON
                    onClickAction = row.action
                }
                group.addRow(item)
            }
            b.sections.addView(group, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = if (first) 0 else dp(12) })
            first = false
            added += filtered.size
        }
        if (added == 0) {
            b.sections.addView(android.widget.TextView(requireContext()).apply {
                text = "没有匹配的设置"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.text_secondary))
                gravity = android.view.Gravity.CENTER
                setPadding(0, dp(48), 0, 0)
            })
        }
    }

    private fun groups() = listOf(
        listOf(
            Row("基本设置", R.drawable.orig_s_basic) { Nav.push(SettingsPageFragment.newInstance("basic")) },
            Row("外观/手势", R.drawable.orig_s_appearance) { Nav.push(SettingsPageFragment.newInstance("appearance")) },
            Row("下载相关", R.drawable.orig_s_download) { Nav.push(SettingsPageFragment.newInstance("download")) },
            Row("广告拦截", R.drawable.orig_s_shield) { Nav.push(AdBlockFragment()) },
            Row("嗅探和播放器", R.drawable.orig_s_sniffer) { Nav.push(SettingsPageFragment.newInstance("sniffer")) }
        ),
        listOf(
            Row("加密 DNS", R.drawable.orig_s_doh) { Nav.push(DohFragment()) },
            Row("脚本管理", R.drawable.orig_m_plugin) { Nav.push(ScriptFragment()) },
            Row("AI 供应商", R.drawable.orig_m_ai, rawIcon = true) { Nav.push(AiFragment()) },
            Row("网站配置", R.drawable.orig_s_site) { Nav.push(SettingsPageFragment.newInstance("site")) },
            Row("UA 管理", R.drawable.orig_s_ua) { Nav.push(SettingsPageFragment.newInstance("ua")) }
        ),
        listOf(
            Row("数据备份", R.drawable.orig_s_backup) { Nav.push(BackupFragment()) },
            Row("隐私与安全", R.drawable.orig_m_incognito) { Nav.push(SettingsPageFragment.newInstance("privacy")) }
        ),
        listOf(
            Row("更多设置", R.drawable.orig_s_more) { Nav.push(SettingsPageFragment.newInstance("more")) },
            Row("关于与帮助", R.drawable.orig_s_about) { Nav.push(AboutFragment()) }
        )
    )

    private data class Row(
        val title: String,
        val icon: Int,
        val rawIcon: Boolean = false,
        val action: () -> Unit
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
