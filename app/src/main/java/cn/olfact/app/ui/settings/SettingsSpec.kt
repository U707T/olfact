package cn.olfact.app.ui.settings

import androidx.fragment.app.Fragment
import cn.olfact.core.api.SettingsKey

/** 设置页的声明式描述：页面 = 若干分组，分组 = 若干行。 */
data class Spec(
    val title: String,
    val sections: List<Section>,
    val footer: String? = null
)

data class Section(
    val rows: List<Row>,
    val title: String? = null,
    val footer: String? = null
)

sealed class Row {
    data class Toggle(
        val key: SettingsKey<Boolean>,
        val title: String,
        val summary: String? = null
    ) : Row()

    data class Choice(
        val key: SettingsKey<String>,
        val title: String,
        val options: List<Pair<String, String>>,
        val summary: String? = null
    ) : Row()

    data class IntChoice(
        val key: SettingsKey<Int>,
        val title: String,
        val options: List<Pair<Int, String>>,
        val summary: String? = null
    ) : Row()

    data class Value(
        val title: String,
        val value: () -> String,
        val onClick: (() -> Unit)? = null,
        val summary: String? = null
    ) : Row()

    data class Action(
        val title: String,
        val summary: String? = null,
        val danger: Boolean = false,
        val onClick: () -> Unit
    ) : Row()

    data class Nav(
        val title: String,
        val summary: String? = null,
        val target: () -> Fragment
    ) : Row()
}
