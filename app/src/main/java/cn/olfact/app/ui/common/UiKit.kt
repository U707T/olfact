package cn.olfact.app.ui.common

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import cn.olfact.app.R
import cn.olfact.core.uikit.SettingGroup
import cn.olfact.core.uikit.SettingItem
import cn.olfact.core.uikit.dpF

/** 页面里反复出现的三种块：分组卡片、说明文字、小节标题。 */
fun Fragment.settingGroup(vararg rows: SettingItem): SettingGroup {
    val group = SettingGroup(requireContext())
    rows.forEach { group.addRow(it) }
    return group
}

fun Fragment.setting(
    title: String,
    value: String? = null,
    summary: String? = null,
    mode: SettingItem.Mode = SettingItem.Mode.NONE,
    onClick: (() -> Unit)? = null
): SettingItem = SettingItem(requireContext()).apply {
    this.title = title
    this.value = value
    this.summary = summary
    this.mode = mode
    this.onClickAction = onClick
}

fun Fragment.toggle(title: String, checked: Boolean, summary: String? = null, onChange: (Boolean) -> Unit): SettingItem =
    SettingItem(requireContext()).apply {
        this.title = title
        this.summary = summary
        mode = SettingItem.Mode.SWITCH
        isCheckedItem = checked
        onCheckedChange = onChange
    }

fun Fragment.sectionTitle(text: String): TextView = TextView(requireContext()).apply {
    this.text = text
    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
    setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
    setPadding(requireContext().dpF(4f), requireContext().dpF(18f), requireContext().dpF(4f), requireContext().dpF(6f))
}

fun Fragment.note(text: String): TextView = TextView(requireContext()).apply {
    this.text = text
    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
    setTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))
    setPadding(requireContext().dpF(6f), requireContext().dpF(10f), requireContext().dpF(6f), requireContext().dpF(4f))
    setLineSpacing(0f, 1.4f)
}

fun Fragment.cardBottomMargin(view: View, dp: Int = 12) {
    val lp = view.layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    )
    lp.topMargin = requireContext().dpF(dp.toFloat())
    view.layoutParams = lp
}
