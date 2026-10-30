package cn.olfact.app.ui.download

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import cn.olfact.app.R
import cn.olfact.core.uikit.Sheet
import cn.olfact.core.uikit.dpF

/** 添加下载：粘链接 → 填名字（可空）→ 入队。 */
object AddDownloadSheet {

    fun show(context: Context, onConfirm: (url: String, name: String?) -> Unit) {
        val sheet = Sheet(context).title("添加下载").subtitle("支持直链与 m3u8；分片合并由 download 模块负责")
        val url = EditText(context).apply {
            hint = "https://example.com/file.mp4"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setBackgroundResource(R.drawable.bg_round_12)
            setPadding(context.dpF(12f), context.dpF(10f), context.dpF(12f), context.dpF(10f))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = context.dpF(20f); rightMargin = context.dpF(20f); topMargin = context.dpF(4f)
            }
        }
        val name = EditText(context).apply {
            hint = "文件名（可空）"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setBackgroundResource(R.drawable.bg_round_12)
            setPadding(context.dpF(12f), context.dpF(10f), context.dpF(12f), context.dpF(10f))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = context.dpF(20f); rightMargin = context.dpF(20f); topMargin = context.dpF(8f)
            }
        }
        val confirm = TextView(context).apply {
            text = "开始下载"
            gravity = android.view.Gravity.CENTER
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(ContextCompat.getColor(context, R.color.white))
            setBackgroundResource(R.drawable.bg_button_primary)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dpF(44f)).apply {
                leftMargin = context.dpF(20f); rightMargin = context.dpF(20f); topMargin = context.dpF(14f); bottomMargin = context.dpF(8f)
            }
            setOnClickListener {
                onConfirm(url.text.toString().trim(), name.text.toString().trim())
                sheet.dismiss()
            }
        }
        sheet.add(url)
        sheet.add(name)
        sheet.add(confirm)
        sheet.show()
        url.requestFocus()
    }
}
