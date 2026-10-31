package cn.olfact.core.uikit

import cn.olfact.core.R
import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * 设置行：`[icon] 标题(+副标题) .......... 值 [开关/勾选/箭头]`。
 *
 * 尺寸对齐原版：行高 56dp、左右 16dp、标题 17sp、值 15sp 灰。
 * 图标只在设置首页出现，子页里通常省略。
 */
class SettingItem @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    enum class Mode { NONE, CHEVRON, SWITCH, CHECKBOX }

    private val iconView = ImageView(context).apply {
        visibility = View.GONE
        layoutParams = LayoutParams(context.dp(24), context.dp(24)).apply { marginEnd = context.dp(16) }
    }
    private val titleView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.uikit_text_body))
        setTextColor(ContextCompat.getColor(context, R.color.uikit_text_primary))
        maxLines = 2
    }
    private val summaryView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.uikit_text_small))
        setTextColor(ContextCompat.getColor(context, R.color.uikit_text_secondary))
        visibility = View.GONE
    }
    private val valueView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.uikit_text_value))
        setTextColor(ContextCompat.getColor(context, R.color.uikit_text_secondary))
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }
    private val switchView = UiSwitch(context).apply { visibility = View.GONE }
    private val checkView = CheckBox(context).apply {
        buttonDrawable = ContextCompat.getDrawable(context, R.drawable.uikit_check)
        visibility = View.GONE
        isClickable = false
        isFocusable = false
        minimumWidth = 0
        minimumHeight = 0
        setPadding(0, 0, 0, 0)
    }
    private val chevronView = ImageView(context).apply {
        setImageResource(R.drawable.uikit_ic_chevron_right)
        imageTintList = ContextCompat.getColorStateList(context, R.color.uikit_text_hint)
        visibility = View.GONE
        layoutParams = LayoutParams(context.dp(16), context.dp(16)).apply { marginStart = context.dp(6) }
    }

    var mode: Mode = Mode.CHEVRON
        set(value) {
            field = value
            applyMode()
        }

    var onCheckedChange: ((Boolean) -> Unit)? = null

    /**
     * 行被点击时执行的动作（跳转 / 弹单选 / 触发命令）。
     *
     * 注意：**不要**再通过 `setOnClickListener` 注册外部监听——行内交互（开关、勾选）
     * 已经占用同一个点击入口，两者统一在这里串起来。
     */
    var onClickAction: (() -> Unit)? = null
        set(value) {
            field = value
            syncClickable()
        }

    /** 行内交互（开关翻转 / 勾选切换），由 [mode] 决定；与 [onClickAction] 叠加执行。 */
    private var internalClick: (() -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dp(56)
        setPadding(context.dp(16), context.dp(10), context.dp(16), context.dp(10))
        isClickable = true
        background = ContextCompat.getDrawable(context, R.drawable.uikit_ripple_row)

        val textColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(summaryView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(2)
            })
        }
        addView(iconView)
        addView(textColumn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(valueView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = context.dp(8)
        })
        addView(switchView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = context.dp(8)
        })
        addView(checkView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = context.dp(8)
        })
        addView(chevronView)

        // 唯一注册点：先跑行内交互，再跑外部动作
        super.setOnClickListener {
            internalClick?.invoke()
            onClickAction?.invoke()
        }
        applyMode()
    }

    var title: String
        get() = titleView.text.toString()
        set(v) {
            titleView.text = v
        }

    var summary: String?
        get() = summaryView.text?.toString()?.ifEmpty { null }
        set(v) {
            summaryView.text = v
            summaryView.visibility = if (v.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

    var value: String?
        get() = valueView.text?.toString()?.ifEmpty { null }
        set(v) {
            valueView.text = v
            valueView.visibility = if (v.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

    var isCheckedItem: Boolean
        get() = if (mode == Mode.CHECKBOX) checkView.isChecked else switchView.isChecked
        set(v) {
            checkView.isChecked = v
            switchView.setChecked(v, false)
        }

    fun setIcon(resId: Int) {
        iconView.setImageResource(resId)
        iconView.imageTintList = ContextCompat.getColorStateList(context, R.color.uikit_text_primary)
        iconView.visibility = View.VISIBLE
    }

    /** 保留图标自带颜色（原版的彩色图标）。 */
    fun setIconOriginal(resId: Int) {
        iconView.setImageResource(resId)
        iconView.imageTintList = null
        iconView.visibility = View.VISIBLE
    }

    fun setIconTint(colorRes: Int) {
        iconView.imageTintList = ContextCompat.getColorStateList(context, colorRes)
    }

    fun setValueColor(colorRes: Int) {
        valueView.setTextColor(ContextCompat.getColor(context, colorRes))
    }

    private fun applyMode() {
        switchView.visibility = if (mode == Mode.SWITCH) View.VISIBLE else View.GONE
        checkView.visibility = if (mode == Mode.CHECKBOX) View.VISIBLE else View.GONE
        chevronView.visibility = if (mode == Mode.CHEVRON) View.VISIBLE else View.GONE
        internalClick = when (mode) {
            Mode.SWITCH -> {
                switchView.onCheckedChange = { checked -> onCheckedChange?.invoke(checked) }
                // 点整行也能切换，增大命中区域
                ({ switchView.toggle() })
            }
            Mode.CHECKBOX -> {
                // 行点击时 toggle，checkView 只负责显示
                ({ checkView.isChecked = !checkView.isChecked; onCheckedChange?.invoke(checkView.isChecked) })
            }
            else -> null
        }
        syncClickable()
    }

    /** 没有动作也没有行内交互时不要「假可点」：不点亮 ripple，也不吃点击。 */
    private fun syncClickable() {
        isClickable = internalClick != null || onClickAction != null
    }
}

internal fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()
