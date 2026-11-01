package cn.olfact.app.ui.download

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import cn.olfact.app.R
import cn.olfact.core.uikit.dpF

/**
 * 添加下载：居中对话框（对齐原版形态），三个字段：网址 / 文件名 / 后缀。
 *
 * 之前是底部弹层 + 两个输入框；原版是居中对话框，后缀单独一栏。
 * 下载器选择（内置 / 系统）暂未暴露——当前策略是「能交给系统就交系统」。
 */
object AddDownloadSheet {

    /** 当前显示的对话框：重复点「新增下载」先收起旧的。 */
    private var current: AlertDialog? = null

    /** Activity 销毁时清掉静态引用。 */
    fun dismiss() {
        current?.takeIf { it.isShowing }?.dismiss()
        current = null
    }

    fun show(context: Context, onConfirm: (url: String, name: String?) -> Unit) {
        current?.takeIf { it.isShowing }?.dismiss()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dpF(20f), context.dpF(8f), context.dpF(20f), context.dpF(4f))
        }

        fun field(hint: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText =
            EditText(context).apply {
                this.hint = hint
                inputType = type
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                setBackgroundResource(R.drawable.bg_round_12)
                setPadding(context.dpF(12f), context.dpF(10f), context.dpF(12f), context.dpF(10f))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = context.dpF(8f) }
            }

        val url = field("https://example.com/file.mp4", InputType.TYPE_TEXT_VARIATION_URI)
        val name = field("文件名（可空）")
        val ext = field("后缀（可空，如 mp4）")

        container.addView(url)
        container.addView(name)
        container.addView(ext)

        val dialog = AlertDialog.Builder(context)
            .setTitle("添加下载")
            .setView(container)
            .setPositiveButton("开始下载") { _, _ ->
                val u = url.text.toString().trim()
                if (u.isBlank()) return@setPositiveButton
                val n = name.text.toString().trim().ifBlank { null }
                val e = ext.text.toString().trim().removePrefix(".")
                val fileName = when {
                    n == null -> null
                    e.isBlank() || n.endsWith(".$e", ignoreCase = true) -> n
                    else -> "$n.$e"
                }
                onConfirm(u, fileName)
            }
            .setNegativeButton("取消", null)
            .create()
        current = dialog
        dialog.setOnDismissListener { if (current === dialog) current = null }
        dialog.show()
    }
}
