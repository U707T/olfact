package cn.olfact.core.uikit

import cn.olfact.core.R
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat

/**
 * 地址栏 / 搜索框。
 *
 * 首页用「假输入框」（点击整条进浏览器），浏览器里用真输入框。
 * 左侧图标表示当前页 DNS 状态：🔒 走 DoH、🌐 走系统 DNS（TECH-PLAN §4.5-9）。
 */
class UrlBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val statusIcon = ImageView(context).apply {
        setImageResource(R.drawable.uikit_ic_globe)
        imageTintList = ContextCompat.getColorStateList(context, R.color.uikit_text_secondary)
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = LayoutParams(context.dpF(18f), context.dpF(18f)).apply { marginStart = context.dpF(14f) }
    }

    val editor: EditText = EditText(context).apply {
        background = null
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.uikit_text_row))
        setTextColor(ContextCompat.getColor(context, R.color.uikit_text_primary))
        setHintTextColor(ContextCompat.getColor(context, R.color.uikit_text_hint))
        isSingleLine = true
        imeOptions = EditorInfo.IME_ACTION_GO
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        setPadding(context.dpF(10f), 0, context.dpF(4f), 0)
        layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
    }

    private val clearIcon = ImageView(context).apply {
        setImageResource(R.drawable.uikit_ic_close)
        imageTintList = ContextCompat.getColorStateList(context, R.color.uikit_text_hint)
        visibility = GONE
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(context.dpF(8f), context.dpF(8f), context.dpF(8f), context.dpF(8f))
        layoutParams = LayoutParams(context.dpF(32f), context.dpF(32f))
    }

    private val actionIcon = ImageView(context).apply {
        setImageResource(R.drawable.uikit_ic_scan)
        imageTintList = ContextCompat.getColorStateList(context, R.color.uikit_text_primary)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(context.dpF(8f), context.dpF(8f), context.dpF(10f), context.dpF(8f))
        layoutParams = LayoutParams(context.dpF(42f), context.dpF(42f))
    }

    var onActionClick: (() -> Unit)? = null
    var onBarClick: (() -> Unit)? = null
    var onTextChanged: ((String) -> Unit)? = null
    var onSubmit: ((String) -> Unit)? = null

    private var progress = 0
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.uikit_accent)
    }
    private val progressRect = RectF()

    var editable: Boolean = true
        set(value) {
            field = value
            editor.isFocusable = value
            editor.isFocusableInTouchMode = value
            editor.isCursorVisible = value
            if (!value) {
                editor.setOnTouchListener { _, _ ->
                    onBarClick?.invoke()
                    true
                }
            } else {
                editor.setOnTouchListener(null)
            }
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = ContextCompat.getDrawable(context, R.drawable.uikit_bg_urlbar)
        minimumHeight = context.dpF(48f)
        clipToPadding = false
        setWillNotDraw(false)


        addView(statusIcon)
        addView(editor)
        addView(clearIcon)
        addView(actionIcon)

        actionIcon.setOnClickListener { onActionClick?.invoke() }
        clearIcon.setOnClickListener {
            editor.setText("")
            onTextChanged?.invoke("")
        }

        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                val editing = hasFocus()
                clearIcon.visibility = if (text.isNotEmpty() && editing) VISIBLE else GONE
                actionIcon.visibility = if (text.isNotEmpty() && editing) GONE else VISIBLE
                onTextChanged?.invoke(text)
            }
        })

        editor.setOnFocusChangeListener { _, hasFocus ->
            clearIcon.visibility = if (hasFocus && editor.text.isNotEmpty()) VISIBLE else GONE
            actionIcon.visibility = if (hasFocus && editor.text.isNotEmpty()) GONE else VISIBLE
            if (hasFocus) editor.setSelection(editor.text.length)
        }

        editor.setOnEditorActionListener { _, actionId, event ->
            val isGo = actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (isGo) {
                onSubmit?.invoke(editor.text.toString())
                true
            } else {
                false
            }
        }

        // 假输入框：点击整条进浏览器
        setOnClickListener {
            if (!editable) onBarClick?.invoke()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (progress in 1..99) {
            val h = context.dpF(2f).toFloat()
            progressRect.set(0f, height - h, width * progress / 100f, height.toFloat())
            canvas.drawRoundRect(progressRect, h / 2f, h / 2f, progressPaint)
        }
    }

    fun setText(text: String?) {
        editor.setText(text.orEmpty())
        editor.setSelection(editor.text.length)
    }

    fun text(): String = editor.text.toString()

    fun setHint(hint: CharSequence) {
        editor.hint = hint
    }

    /** true = 当前页走加密 DNS */
    fun setSecure(secure: Boolean) {
        statusIcon.setImageResource(if (secure) R.drawable.uikit_ic_lock else R.drawable.uikit_ic_globe)
        statusIcon.imageTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(context, if (secure) R.color.uikit_accent else R.color.uikit_text_secondary)
        )
        statusIcon.contentDescription = if (secure) "加密 DNS 已生效" else "系统 DNS"
    }

    fun setActionIcon(resId: Int) {
        actionIcon.setImageResource(resId)
    }

    fun setProgress(value: Int) {
        progress = value.coerceIn(0, 100)
        invalidate()
    }

    fun focusEditor() {
        editor.requestFocus()
        editor.post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(editor, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun clearFocusCompat() {
        editor.clearFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(editor.windowToken, 0)
    }
}
