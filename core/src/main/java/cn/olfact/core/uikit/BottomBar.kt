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
 * 底部栏，逐像素对齐原版 `view_stub_browser_bottom.xml`：
 *
 * ```
 * [首页] [刷新] [ 标题胶囊（占剩余宽度） ] [标签数] [菜单]
 *   36dp   36dp          weight=1            36dp   36dp
 * ```
 *
 * 尺寸/间距全部照抄原版：栏高 54dp、容器左右 padding 10dp、图标卡 36dp（内 padding 5dp
 * → 图标 26dp）、home 前 10dp、home→refresh 15dp、refresh→胶囊 18dp、胶囊→标签数 18dp、
 * 标签数→菜单 15dp、菜单后 10dp。
 *
 * 颜色也照抄：图标与胶囊文字都是 `blackText2 #444444`，**没有选中态高亮**——
 * 原版点完「刷新/菜单」后首页图标不会变灰，选中态只体现在胶囊文字上。
 *
 * 长按与原版一致：每个槽位都能长按（首页回首页、刷新强制刷新、胶囊出链接菜单、
 * 标签数出「新建窗口/清除其它/清除全部」）。
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
        val kind: Kind = Kind.ICON,
        /** 无障碍播报；图标槽必须给，否则 TalkBack 只读「按钮」。 */
        val description: String = label,
        /** 原版是相对布局的 marginStart/marginEnd，这里保留同样的间距。 */
        val marginStart: Float = 0f,
        val marginEnd: Float = 0f
    )

    private val slotContainer = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dpF(10f), 0, context.dpF(10f), 0)
    }

    private val slots = LinkedHashMap<String, View>()
    private val icons = LinkedHashMap<String, ImageView>()
    private val counts = LinkedHashMap<String, ImageView>()
    private val pills = LinkedHashMap<String, TextView>()

    var onSelect: ((String) -> Unit)? = null

    /** 长按槽位（原版每个槽位都有 longClick，见 WebViewActivity#initBottomBar）。 */
    var onLongSelect: ((String, View) -> Unit)? = null

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
        // 原版 `bottom_bar_bg` 高 54dp（含 1dp 顶部分隔线）。
        addView(slotContainer, LayoutParams(LayoutParams.MATCH_PARENT, context.dpF(53f)))
    }

    fun configure(items: List<Item>, style: Style = Style.ICON_ONLY) {
        this.style = style
        slotContainer.removeAllViews()
        slots.clear(); icons.clear(); counts.clear(); pills.clear()

        items.forEach { item ->
            // 纯图标模式没有标题胶囊：把它收缩成一个「搜索/地址」图标。
            // 直接省略会让这个模式完全没有输入网址的入口（只能先回首页）。
            val effective = if (item.kind == Kind.PILL && style == Style.ICON_ONLY) {
                item.copy(kind = Kind.ICON, icon = R.drawable.uikit_ic_search, description = "地址与搜索")
            } else {
                item
            }

            val slot = when (effective.kind) {
                Kind.PILL -> buildPill(effective)
                Kind.COUNT -> buildCountSlot(effective)
                Kind.ICON -> buildIconSlot(effective)
            }
            val lp = if (effective.kind == Kind.PILL || style == Style.ICON_ONLY) {
                LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            } else {
                LayoutParams(context.dpF(36f), LayoutParams.MATCH_PARENT)
            }
            lp.marginStart = context.dpF(item.marginStart)
            lp.marginEnd = context.dpF(item.marginEnd)
            slotContainer.addView(slot, lp)
            slots[item.id] = slot

            // 点击/长按都只回调宿主：底栏自身不改选中态（原版没有这一层）。
            slot.setOnClickListener { onSelect?.invoke(item.id) }
            slot.setOnLongClickListener {
                val handler = onLongSelect ?: return@setOnLongClickListener false
                handler(item.id, slot)
                true
            }
            slot.contentDescription = item.description.ifBlank { item.id }
        }
        select(items.firstOrNull()?.id, notify = false)
    }

    private fun buildIconSlot(item: Item): View {
        val container = FrameLayout(context).apply {
            background = ContextCompat.getDrawable(context, R.drawable.uikit_ripple_row)
        }
        val icon = ImageView(context).apply {
            setImageResource(item.icon)
            imageTintList = ContextCompat.getColorStateList(context, R.color.uikit_text_bar)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = item.description.ifBlank { null }
            layoutParams = FrameLayout.LayoutParams(context.dpF(26f), context.dpF(26f), Gravity.CENTER)
        }
        container.addView(icon)
        icons[item.id] = icon
        return container
    }

    /**
     * 标签数：直接使用原版 `icon_web_discory_N`（数字画在图标里）。
     * 超过 10 个时退回不带数字的窗口图标，contentDescription 仍报真实数量。
     */
    private fun buildCountSlot(item: Item): View {
        val container = FrameLayout(context).apply {
            background = ContextCompat.getDrawable(context, R.drawable.uikit_ripple_row)
        }
        val image = ImageView(context).apply {
            setImageResource(R.drawable.uikit_ic_tab_count_1)
            imageTintList = ContextCompat.getColorStateList(context, R.color.uikit_text_bar)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "1 个标签页"
            layoutParams = FrameLayout.LayoutParams(context.dpF(26f), context.dpF(26f), Gravity.CENTER)
        }
        container.addView(image)
        counts[item.id] = image
        return container
    }

    private fun buildPill(item: Item): View {
        // 原版 `bottom_bar_text_bg`：gray_rice #F5F5F5、32dp 高、圆角 18dp、文字 #444444。
        val pill = TextView(context).apply {
            text = item.label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.NORMAL)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(context.dpF(12f), 0, context.dpF(12f), 0)
            background = ContextCompat.getDrawable(context, R.drawable.uikit_bg_pill)
            setTextColor(ContextCompat.getColor(context, R.color.uikit_text_bar))
        }
        val wrapper = FrameLayout(context).apply {
            background = ContextCompat.getDrawable(context, R.drawable.uikit_ripple_row)
            addView(pill, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, context.dpF(32f), Gravity.CENTER
            ))
        }
        pills[item.id] = pill
        return wrapper
    }

    fun setPillText(id: String, text: CharSequence?) {
        pills[id]?.text = text?.takeIf { it.isNotBlank() } ?: "主页"
    }

    /**
     * 记录当前页面槽位（只有 home / search / tabs 会用到）。
     *
     * 原版底栏图标始终同色，所以这里**不**改 tint；保留 [selectedId] 仅供宿主查询。
     */
    fun select(id: String?, notify: Boolean = false) {
        selectedId = id
        if (notify) onSelect?.invoke(id ?: return)
    }

    /** 换某个槽位的图标（底栏第二格在可前进时显示前进箭头）。 */
    fun setIcon(id: String, iconRes: Int) {
        icons[id]?.setImageResource(iconRes)
    }

    fun setBadge(id: String, count: Int) {
        val image = counts[id] ?: return
        val safe = count.coerceAtLeast(0)
        image.setImageResource(
            when {
                safe in 1..10 -> COUNT_ICONS[safe - 1]
                else -> R.drawable.uikit_ic_tab_count_many
            }
        )
        image.contentDescription = "$safe 个标签页"
    }

    fun currentId(): String? = selectedId

    private companion object {
        val COUNT_ICONS = intArrayOf(
            R.drawable.uikit_ic_tab_count_1,
            R.drawable.uikit_ic_tab_count_2,
            R.drawable.uikit_ic_tab_count_3,
            R.drawable.uikit_ic_tab_count_4,
            R.drawable.uikit_ic_tab_count_5,
            R.drawable.uikit_ic_tab_count_6,
            R.drawable.uikit_ic_tab_count_7,
            R.drawable.uikit_ic_tab_count_8,
            R.drawable.uikit_ic_tab_count_9,
            R.drawable.uikit_ic_tab_count_10
        )
    }
}
