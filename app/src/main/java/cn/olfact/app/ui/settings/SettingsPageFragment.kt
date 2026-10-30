package cn.olfact.app.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import cn.olfact.app.R
import cn.olfact.app.data.AppSettings
import cn.olfact.app.databinding.FragmentSettingsPageBinding
import cn.olfact.app.nav.Nav
import cn.olfact.app.ui.common.Dialogs
import cn.olfact.core.uikit.SettingGroup
import cn.olfact.core.uikit.SettingItem

/**
 * 通用设置页渲染器：拿着 [Spec] 画分组卡片。
 *
 * 好处是 13 个设置子页只有数据、没有各自的一份布局；
 * 视觉与交互（开关/单选/输入/跳转）只维护一处。
 */
class SettingsPageFragment : Fragment(R.layout.fragment_settings_page) {

    private var binding: FragmentSettingsPageBinding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentSettingsPageBinding.bind(view)
        binding = b

        val id = requireArguments().getString(ARG_ID).orEmpty()
        val spec = SettingsSpecs.build(id)
        b.header.title = spec.title
        b.header.onBack = { Nav.pop() }
        b.sections.removeAllViews()

        spec.sections.forEach { section ->
            section.title?.let { b.sections.addView(sectionLabel(it)) }
            val group = SettingGroup(requireContext())
            section.rows.forEach { row -> group.addRow(buildRow(row)) }
            b.sections.addView(group, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) })
            section.footer?.let { b.sections.addView(footerLabel(it)) }
        }
        spec.footer?.let { b.sections.addView(footerLabel(it)) }
    }

    private fun sectionLabel(text: String) = TextView(requireContext()).apply {
        this.text = text
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
        setPadding(dp(4), dp(16), dp(4), dp(6))
    }

    private fun footerLabel(text: String) = TextView(requireContext()).apply {
        this.text = text
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))
        setPadding(dp(6), dp(8), dp(6), dp(4))
        setLineSpacing(0f, 1.35f)
    }

    private fun buildRow(row: Row): View = when (row) {
        is Row.Toggle -> SettingItem(requireContext()).apply {
            title = row.title
            summary = row.summary
            mode = SettingItem.Mode.SWITCH
            isCheckedItem = AppSettings.get(row.key)
            onCheckedChange = { AppSettings.set(row.key, it) }
        }

        is Row.Choice -> SettingItem(requireContext()).apply {
            title = row.title
            summary = row.summary
            mode = SettingItem.Mode.CHEVRON
            value = row.options.firstOrNull { it.first == AppSettings.get(row.key) }?.second ?: "未设置"
            onClickAction = {
                val current = AppSettings.get(row.key)
                val index = row.options.indexOfFirst { it.first == current }.coerceAtLeast(0)
                Dialogs.choose(requireContext(), row.title, row.options.map { it.second }, index) { picked ->
                    AppSettings.set(row.key, row.options[picked].first)
                    refresh()
                }
            }
        }

        is Row.IntChoice -> SettingItem(requireContext()).apply {
            title = row.title
            summary = row.summary
            mode = SettingItem.Mode.CHEVRON
            value = row.options.firstOrNull { it.first == AppSettings.get(row.key) }?.second ?: "未设置"
            onClickAction = {
                val current = AppSettings.get(row.key)
                val index = row.options.indexOfFirst { it.first == current }.coerceAtLeast(0)
                Dialogs.choose(requireContext(), row.title, row.options.map { it.second }, index) { picked ->
                    AppSettings.set(row.key, row.options[picked].first)
                    refresh()
                }
            }
        }

        is Row.Value -> SettingItem(requireContext()).apply {
            title = row.title
            summary = row.summary
            mode = if (row.onClick != null) SettingItem.Mode.CHEVRON else SettingItem.Mode.NONE
            value = row.value()
            onClickAction = row.onClick
        }

        is Row.Action -> SettingItem(requireContext()).apply {
            title = row.title
            summary = row.summary
            mode = SettingItem.Mode.NONE
            onClickAction = row.onClick
        }

        is Row.Nav -> SettingItem(requireContext()).apply {
            title = row.title
            summary = row.summary
            mode = SettingItem.Mode.CHEVRON
            onClickAction = { Nav.push(row.target()) }
        }
    }

    private fun refresh() {
        val b = binding ?: return
        val specs = SettingsSpecs.build(requireArguments().getString(ARG_ID).orEmpty())
        b.sections.removeAllViews()
        specs.sections.forEach { section ->
            section.title?.let { b.sections.addView(sectionLabel(it)) }
            val group = SettingGroup(requireContext())
            section.rows.forEach { row -> group.addRow(buildRow(row)) }
            b.sections.addView(group, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) })
            section.footer?.let { b.sections.addView(footerLabel(it)) }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val ARG_ID = "page_id"
        fun newInstance(id: String) = SettingsPageFragment().apply {
            arguments = Bundle().apply { putString(ARG_ID, id) }
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
