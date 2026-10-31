package cn.olfact.app.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import cn.olfact.app.MainActivity
import cn.olfact.app.R
import cn.olfact.app.data.AppSettings
import cn.olfact.app.data.HistoryStore
import cn.olfact.app.data.K
import cn.olfact.app.data.RuntimeFlags
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
            onCheckedChange = {
                AppSettings.set(row.key, it)
                onSettingChanged(row.key.name, it)
            }
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
                    val pickedValue = row.options[picked].first
                    AppSettings.set(row.key, pickedValue)
                    if (!onSettingChanged(row.key.name, pickedValue)) refresh()
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
                    val pickedValue = row.options[picked].first
                    AppSettings.set(row.key, pickedValue)
                    if (!onSettingChanged(row.key.name, pickedValue)) refresh()
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

    /**
     * 设置项改变后的即时生效逻辑。
     * @return true 表示已经触发 Activity 重建，调用方不要再 refresh 旧 View。
     */
    private fun onSettingChanged(key: String, value: Any?): Boolean {
        when (key) {
            K.NIGHT_MODE.name -> {
                val mode = when (value as? Int) {
                    1 -> AppCompatDelegate.MODE_NIGHT_YES
                    2 -> AppCompatDelegate.MODE_NIGHT_NO
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                // AppCompat 会重建 Activity；同菜单里的夜间开关走同一条路，行为保持一致。
                AppCompatDelegate.setDefaultNightMode(mode)
                return true
            }

            K.FONT_SCALE.name -> {
                requireActivity().recreate()
                return true
            }

            K.BOTTOM_STYLE.name -> {
                (requireActivity() as? MainActivity)?.applyBottomBarStyle()
            }

            K.EYE_CARE.name -> {
                (requireActivity() as? MainActivity)?.applyEyeCare()
            }

            K.HISTORY_LIMIT.name -> {
                HistoryStore.trim(requireContext())
            }

            // 这些设置只影响新页面：标记一下，浏览器下次回到前台自动 reload。
            "web.text_zoom", "web.no_image", "web.night", "web.desktop", "web.js" -> {
                RuntimeFlags.reloadPagesOnResume = true
            }
        }
        return false
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
