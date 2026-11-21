package cn.olfact.core.uikit
/** Item data shape only; no BottomBar rendering is tested here. */
class BottomBar {
    enum class Kind { ICON, PILL, COUNT }
    data class Item(val id: String, val icon: Int = 0, val label: String = "", val kind: Kind = Kind.ICON,
        val description: String = "", val marginStart: Float = 0f, val marginEnd: Float = 0f)
}
