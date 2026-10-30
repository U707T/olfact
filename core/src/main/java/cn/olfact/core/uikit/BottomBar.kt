package cn.olfact.core.uikit

import cn.olfact.core.R
import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * 底部栏。原版有 4 种样式，这里收敛成 2 种（见 B5 任务书）：
 *
 * - [Style.ICON_ONLY]：只有图标，选中变深（默认）
 * - [Style.PILL]：中间是一个胶囊文字按钮（原版「主页」），两侧仍是图标
 *
 * 5 个槽位：首页 / 标签页 / 主页(pill) / 下载 / 菜单。
 */
class BottomBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    enum class Style { ICON_ONLY, PILL }

    data class Item(
        val id: String,
        val icon: Int,
        val label: String = "",
        val pill: Boolean = false
    )

    private val slotContainer = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private val slots = LinkedHashMap<String, View>()
    private val icons = LinkedHashMap<String, ImageView>()
    private val badges = LinkedHashMap<String, TextView>()

    var onSelect: ((String) -> Unit)? = null

    var style: Style = Style.ICON_ONLY
        private set

    private var selectedId: String? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(ContextCompat.getColor(context, R.color.uikit_card))
        addView(View(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(ContextCompat.getColor(context, R.color.uikit_divider))
        })
        addView(slotContainer, LayoutParams(LayoutParams.MATCH_PARENT, context.dpF(56f)))
    }

    fun configure(items: List<Item>, style: Style = Style.ICON_ONLY) {
        this.style = style
        slotContainer.removeAllViews()
        slots.clear(); icons.clear(); badges.clear()

        items.forEach { item ->
            val slot = if (item.pill) buildPill(item) else buildIconSlot(item)
            val lp = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            slotContainer.addView(slot, lp)
            slots[item.id] = slot
            slot.setOnClickListener {
                select(item.id, notify = true)
            }
        }
        select(items.firstOrNull()?.id, notify = false)
    }

    private fun buildIconSlot(item: Item): View {
        val container = FrameLayout(context)
        val icon = ImageView(context).apply {
            setImageResource(item.icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(context.dpF(24f), context.dpF(24f), Gravity.CENTER)
        }
        val badge = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            setTextColor(ContextCompat.getColor(context, R.color.uikit_white))
            setBackgroundResource(R.drawable.uikit_bg_badge)
            gravity = Gravity.CENTER
            visibility = View.GONE
            minWidth = context.dpF(15f)
            setPadding(context.dpF(4f), 0, context.dpF(4f), 0)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, context.dpF(15f), Gravity.CENTER
            ).apply { topMargin = context.dpF(6f); marginStart = context.dpF(18f) }
        }
        container.addView(icon)
        container.addView(badge)
        icons[item.id] = icon
        badges[item.id] = badge
        return container
    }

    private fun buildPill(item: Item): View {
        val pill = TextView(context).apply {
            text = item.label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(context.dpF(18f), context.dpF(7f), context.dpF(18f), context.dpF(7f))
            background = ContextCompat.getDrawable(context, R.drawable.uikit_bg_pill)
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            tag = "pill"
        }
        val wrapper = FrameLayout(context).apply {
            addView(pill, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
            ))
        }
        return wrapper
    }

    fun select(id: String?, notify: Boolean = false) {
        selectedId = id
        val active = ContextCompat.getColor(context, R.color.uikit_text_primary)
        val inactive = ContextCompat.getColor(context, R.color.uikit_text_secondary)
        icons.forEach { (key, icon) ->
            icon.imageTintList = android.content.res.ColorStateList.valueOf(if (key == id) active else inactive)
        }
        slots.forEach { (key, view) ->
            val pill = view.findViewWithTag<TextView>("pill")
            if (pill != null) {
                val isActive = key == id
                pill.setTextColor(if (isActive) ContextCompat.getColor(context, R.color.uikit_text_inverse) else ContextCompat.getColor(context, R.color.uikit_text_primary))
                pill.setBackgroundResource(if (isActive) R.drawable.uikit_bg_pill_dark else R.drawable.uikit_bg_pill)
            }
        }
        if (notify) onSelect?.invoke(id ?: return)
    }

    fun setBadge(id: String, count: Int) {
        val badge = badges[id] ?: return
        if (count <= 0) {
            badge.visibility = View.GONE
        } else {
            badge.visibility = View.VISIBLE
            badge.text = if (count > 99) "99+" else count.toString()
        }
    }

    fun currentId(): String? = selectedId
}
