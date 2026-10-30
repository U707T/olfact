package cn.olfact.app.ui.settings

import android.os.Bundle
import android.view.View
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
 */
class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private var binding: FragmentSettingsBinding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentSettingsBinding.bind(view)
        binding = b
        b.header.title = "设置"
        b.header.onBack = { Nav.pop() }

        val groups = listOf(
            listOf(
                Row("基本设置", R.drawable.ic_settings) { Nav.push(SettingsPageFragment.newInstance("basic")) },
                Row("外观/手势", R.drawable.ic_eye) { Nav.push(SettingsPageFragment.newInstance("appearance")) },
                Row("下载相关", R.drawable.ic_download) { Nav.push(SettingsPageFragment.newInstance("download")) },
                Row("广告拦截", R.drawable.ic_shield) { Nav.push(AdBlockFragment()) },
                Row("嗅探和播放器", R.drawable.ic_play) { Nav.push(SettingsPageFragment.newInstance("sniffer")) }
            ),
            listOf(
                Row("加密 DNS", R.drawable.ic_lock) { Nav.push(DohFragment()) },
                Row("脚本管理", R.drawable.ic_code) { Nav.push(ScriptFragment()) },
                Row("AI 供应商", R.drawable.ic_ai) { Nav.push(AiFragment()) },
                Row("网站配置", R.drawable.ic_globe) { Nav.push(SettingsPageFragment.newInstance("site")) },
                Row("UA 管理", R.drawable.ic_phone) { Nav.push(SettingsPageFragment.newInstance("ua")) }
            ),
            listOf(
                Row("数据备份", R.drawable.ic_cloud_up) { Nav.push(BackupFragment()) },
                Row("隐私与安全", R.drawable.ic_key) { Nav.push(SettingsPageFragment.newInstance("privacy")) }
            ),
            listOf(
                Row("更多设置", R.drawable.ic_list) { Nav.push(SettingsPageFragment.newInstance("more")) },
                Row("关于与帮助", R.drawable.ic_info) { Nav.push(AboutFragment()) }
            )
        )

        b.sections.removeAllViews()
        groups.forEachIndexed { index, rows ->
            val group = SettingGroup(requireContext())
            rows.forEach { row ->
                val item = SettingItem(requireContext()).apply {
                    title = row.title
                    setIcon(row.icon)
                    mode = SettingItem.Mode.CHEVRON
                    onClickAction = row.action
                }
                group.addRow(item)
            }
            b.sections.addView(group, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = if (index == 0) 0 else dp(12) })
        }
    }

    private data class Row(val title: String, val icon: Int, val action: () -> Unit)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
