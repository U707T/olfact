package cn.olfact.core.uikit

import cn.olfact.core.R
import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
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
 * 底部栏，按原版 `view_stub_browser_bottom*.xml` 的五槽布局：
 *
 * ```
 * [首页] [刷新] [ 标题胶囊（占大半个宽度） ] [标签数] [菜单]
 * ```
 *
 * 三个槽位类型：
 * - [Kind.ICON]：固定宽度图标；
 * - [Kind.PILL]：**拉宽**的胶囊，显示当前页面标题（无页面时显示「主页」）；
 * - [Kind.COUNT]：圆角方框 + 居中数字（原版是「标签页数量」）。
 *
 * 图标一律用原版矢量资源（`orig_b_*`），只在 ImageView 上做 tint 以适配深色模式。
 */
class BottomBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    enum class Style { ICON_ONLY, PILL }

    enum class Kind { ICON, PILL, COUNT }

    data class Item(
        val id: String,
        val icon: Int = 0,
        val label: String = "",
        val kind: Kind = Kind.ICON
    )

    private val slotContainer = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dpF(4f), 0, context.dpF(4f), 0)
    }

    private val slots = LinkedHashMap<String, View>()
    private val icons = LinkedHashMap<String, ImageView>()
    private val badges = LinkedHashMap<String, TextView>()
    private val pills = LinkedHashMap<String, TextView>()

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
        // 原版底栏 50dp；图标 30dp，上下各留 10dp
        addView(slotContainer, LayoutParams(LayoutParams.MATCH_PARENT, context.dpF(50f)))
    }

    fun configure(items: List<Item>, style: Style = Style.ICON_ONLY) {
        this.style = style
        slotContainer.removeAllViews()
        slots.clear(); icons.clear(); badges.clear(); pills.clear()

        items.forEach { item ->
            val slot = when (item.kind) {
                Kind.PILL -> buildPill(item)
                Kind.COUNT -> buildCountSlot(item)
                Kind.ICON -> buildIconSlot(item)
            }
            val weight = if (item.kind == Kind.PILL) 3.4f else 1f
            slotContainer.addView(slot, LayoutParams(0, LayoutParams.MATCH_PARENT, weight))
            slots[item.id] = slot
            slot.setOnClickListener { select(item.id, notify = true) }
        }
        select(items.firstOrNull()?.id, notify = false)
    }

    private fun buildIconSlot(item: Item): View {
        val container = FrameLayout(context)
        val icon = ImageView(context).apply {
            setImageResource(item.icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(context.dpF(30f), context.dpF(30f), Gravity.CENTER)
        }
        container.addView(icon)
        icons[item.id] = icon
        return container
    }

    /** 标签数：圆角方框 + 居中数字（方框是 shape，数字由 [setBadge] 填）。 */
    private fun buildCountSlot(item: Item): View {
        val container = FrameLayout(context)
        val box = View(context).apply {
            setBackgroundResource(R.drawable.uikit_bg_count)
            layoutParams = FrameLayout.LayoutParams(context.dpF(26f), context.dpF(26f), Gravity.CENTER)
        }
        val badge = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.uikit_text_primary))
            gravity = Gravity.CENTER
            text = "1"
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
            )
        }
        container.addView(box)
        container.addView(badge)
        badges[item.id] = badge
        return container
    }

    private fun buildPill(item: Item): View {
        val pill = TextView(context).apply {
            text = item.label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(context.dpF(16f), context.dpF(7f), context.dpF(16f), context.dpF(7f))
            background = ContextCompat.getDrawable(context, R.drawable.uikit_bg_pill)
            setTextColor(ContextCompat.getColor(context, R.color.uikit_text_primary))
            tag = "pill"
        }
        val wrapper = FrameLayout(context).apply {
            setPadding(context.dpF(4f), 0, context.dpF(4f), 0)
            addView(pill, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
            ))
        }
        pills[item.id] = pill
        return wrapper
    }

    /** 胶囊文字：浏览器里传页面标题，其它页面传「主页」。 */
    fun setPillText(id: String, text: CharSequence?) {
        pills[id]?.text = text?.takeIf { it.isNotBlank() } ?: "主页"
    }

    fun select(id: String?, notify: Boolean = false) {
        selectedId = id
        val active = ContextCompat.getColor(context, R.color.uikit_text_primary)
        val inactive = ContextCompat.getColor(context, R.color.uikit_text_secondary)
        icons.forEach { (key, icon) ->
            icon.imageTintList = android.content.res.ColorStateList.valueOf(if (key == id) active else inactive)
        }
        pills.forEach { (key, pill) ->
            pill.setTextColor(if (key == id) active else ContextCompat.getColor(context, R.color.uikit_text_primary))
        }
        if (notify) onSelect?.invoke(id ?: return)
    }

    /** 换某个槽位的图标（底栏第二格在浏览器里是刷新、在其它页面是标签页）。 */
    fun setIcon(id: String, iconRes: Int) {
        icons[id]?.setImageResource(iconRes)
    }

    fun setBadge(id: String, count: Int) {
        val badge = badges[id] ?: return
        badge.text = when {
            count <= 0 -> "1"
            count > 99 -> "99+"
            else -> count.toString()
        }
    }

    fun currentId(): String? = selectedId
}
