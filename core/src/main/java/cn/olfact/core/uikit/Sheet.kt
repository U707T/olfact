package cn.olfact.core.uikit

import cn.olfact.core.R
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * 底部弹层。主菜单九宫格、地址栏候选、添加下载、排序菜单都用它。
 *
 * 用 Dialog 而不是 DialogFragment：这些弹层生命周期极短，不需要参与 Fragment 回栈。
 */
class Sheet(private val ctx: Context) : Dialog(ctx, R.style.UikitSheetDialog) {

    private val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = ContextCompat.getDrawable(ctx, R.drawable.uikit_bg_sheet)
    }

    private val body = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, ctx.dpF(4f), 0, ctx.dpF(8f))
    }

    private var titleView: TextView? = null

    init {
        val handle = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ctx.dpF(36f), ctx.dpF(4f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = ctx.dpF(8f)
            }
            background = roundedBg(ContextCompat.getColor(ctx, R.color.uikit_divider), 2f)
        }
        root.addView(handle)
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.45f)
        }
        setCanceledOnTouchOutside(true)
    }

    fun title(text: CharSequence): Sheet {
        val tv = TextView(ctx).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(ContextCompat.getColor(ctx, R.color.uikit_text_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(ctx.dpF(20f), ctx.dpF(14f), ctx.dpF(20f), ctx.dpF(8f))
        }
        titleView = tv
        body.addView(tv)
        return this
    }

    fun subtitle(text: CharSequence): Sheet {
        body.addView(TextView(ctx).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ContextCompat.getColor(ctx, R.color.uikit_text_secondary))
            setPadding(ctx.dpF(20f), 0, ctx.dpF(20f), ctx.dpF(10f))
        })
        return this
    }

    fun add(view: View): Sheet {
        body.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return this
    }

    fun addRow(row: View): Sheet {
        body.addView(row)
        return this
    }

    fun body(): LinearLayout = body

    fun updateTitle(text: CharSequence) {
        titleView?.text = text
    }
}
