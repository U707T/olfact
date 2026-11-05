package cn.olfact.app.ui.ai

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import cn.olfact.app.R
import cn.olfact.app.data.AppLog
import cn.olfact.app.nav.Nav
import cn.olfact.core.uikit.Sheet
import cn.olfact.core.uikit.dpF

/**
 * AI 结果的流式面板：标题 + 状态行 + 可滚动的正文，结束时可复制。
 *
 * 用 [Sheet] 而不是新 Fragment：AI 结果是一次性的，不参与回栈；
 * 正文要能选中复制，所以用可滚动 + `textIsSelectable`。
 */
class AiSheet(private val activity: Activity, title: String) {

    private val sheet = Sheet(activity).title(title)

    private val statusView = TextView(activity).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
        setPadding(activity.dpF(20f), 0, activity.dpF(20f), activity.dpF(8f))
        text = "准备中…"
    }

    private val bodyView = TextView(activity).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        setTextIsSelectable(true)
        setLineSpacing(0f, 1.35f)
        setPadding(activity.dpF(20f), activity.dpF(4f), activity.dpF(20f), activity.dpF(16f))
        typeface = Typeface.DEFAULT
    }

    private val scroll = ScrollView(activity).apply {
        addView(bodyView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, activity.dpF(320f)
        )
    }

    private val footer = TextView(activity).apply {
        text = "复制结果"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(ContextCompat.getColor(activity, R.color.text_accent))
        gravity = android.view.Gravity.CENTER
        setPadding(activity.dpF(16f), activity.dpF(14f), activity.dpF(16f), activity.dpF(18f))
        setOnClickListener {
            val text = bodyView.text?.toString().orEmpty()
            if (text.isBlank()) {
                Nav.toast("还没有结果")
            } else {
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("ai", text))
                Nav.toast("已复制")
            }
        }
    }

    private val text = StringBuilder()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var flushPending = false

    /**
     * 流式增量可能几十毫秒就来一段；每段都 setText 会整块重新排版。
     * 80ms 合并一次，既跟得上又不会把主线程压满。
     */
    private val flush = object : Runnable {
        override fun run() {
            flushPending = false
            bodyView.text = text.toString()
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    init {
        current?.dismiss()
        current = this
        sheet.add(statusView)
        sheet.add(scroll)
        sheet.add(footer)
    }

    fun show() {
        if (activity.isFinishing) return
        runCatching { sheet.show() }.onFailure { AppLog.w("ai", "面板打不开：${it.message}") }
    }

    fun setStatus(text: CharSequence) {
        statusView.text = text
    }

    fun append(delta: String) {
        text.append(delta)
        if (!flushPending) {
            flushPending = true
            handler.postDelayed(flush, 80L)
        }
    }

    fun finish(status: String) {
        handler.removeCallbacks(flush)
        if (flushPending || bodyView.text.isNullOrEmpty()) flush.run()
        statusView.text = status
    }

    fun dismiss() {
        handler.removeCallbacks(flush)
        runCatching { sheet.dismiss() }
        if (current === this) current = null
    }

    companion object {
        /** 同一时间只保留一个 AI 结果面板。 */
        private var current: AiSheet? = null

        /** Activity 销毁时收起，避免静态引用持有已销毁的 Activity。 */
        fun dismissCurrent() {
            current?.dismiss()
            current = null
        }
    }
}
