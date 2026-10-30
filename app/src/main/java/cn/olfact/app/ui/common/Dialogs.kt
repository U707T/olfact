package cn.olfact.app.ui.common

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import cn.olfact.core.uikit.dpF

object Dialogs {

    /** 单选对话框。 */
    fun choose(
        context: Context,
        title: String,
        labels: List<String>,
        checkedIndex: Int,
        onPick: (Int) -> Unit
    ) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), checkedIndex) { dialog, which ->
                dialog.dismiss()
                onPick(which)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 文本输入对话框。 */
    fun input(
        context: Context,
        title: String,
        initial: String = "",
        hint: String = "",
        multiline: Boolean = false,
        onConfirm: (String) -> Unit
    ) {
        val edit = EditText(context).apply {
            setText(initial)
            this.hint = hint
            inputType = if (multiline) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            else InputType.TYPE_CLASS_TEXT
            minLines = if (multiline) 4 else 1
            setPadding(context.dpF(20f), context.dpF(8f), context.dpF(20f), context.dpF(8f))
        }
        val wrapper = LinearLayout(context).apply { addView(edit) }
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(wrapper)
            .setPositiveButton("确定") { _, _ -> onConfirm(edit.text.toString()) }
            .setNegativeButton("取消", null)
            .show()
    }

    fun confirm(context: Context, title: String, message: String, danger: Boolean = false, onConfirm: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(if (danger) "删除" else "确定") { _, _ -> onConfirm() }
            .setNegativeButton("取消", null)
            .show()
    }
}
