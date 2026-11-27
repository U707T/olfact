package cn.olfact.app.ui.settings.pages

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import androidx.fragment.app.Fragment
import cn.olfact.app.R
import cn.olfact.app.data.AppLog
import cn.olfact.app.data.AppSettings
import cn.olfact.app.data.K
import cn.olfact.app.databinding.FragmentPageBinding
import cn.olfact.app.nav.Nav
import cn.olfact.app.ui.common.Dialogs
import cn.olfact.app.ui.common.cardBottomMargin
import cn.olfact.app.ui.common.note
import cn.olfact.app.ui.common.sectionTitle
import cn.olfact.app.ui.common.setting
import cn.olfact.app.ui.common.settingGroup
import cn.olfact.app.ui.common.toggle
import cn.olfact.core.api.OlfactServices
import cn.olfact.core.api.UserScript
import cn.olfact.core.uikit.ListRow
import cn.olfact.core.uikit.SettingGroup
import cn.olfact.core.uikit.UiSwitch
import java.util.UUID

/** 脚本管理：列表 + 开关 + 新建/编辑 + 全局注入开关。 */
class ScriptFragment : Fragment(R.layout.fragment_page) {

    private var binding: FragmentPageBinding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding = FragmentPageBinding.bind(view)
        binding!!.header.title = "脚本管理"
        binding!!.header.onBack = { Nav.pop() }
        binding!!.header.addAction(R.drawable.ic_plus, "新建脚本") { edit(null) }
        render()
    }

    private fun render() {
        val b = binding ?: return
        val ctx = requireContext()
        val store = OlfactServices.scriptStore
        val scripts = store?.all().orEmpty()
        b.content.removeAllViews()

        if (store == null) {
            b.content.addView(note("脚本模块未就绪：暂时无法保存或管理脚本，请稍后重试。"))
        }

        b.content.addView(sectionTitle("注入"))
        b.content.addView(settingGroup(
            toggle("启用 UserScript", AppSettings.get(K.SCRIPT_ENABLED), "在 document start 注入匹配脚本") { on ->
                AppSettings.set(K.SCRIPT_ENABLED, on)
            },
            setting("GM_* API", "部分可用", "GM_info 可用；GM_setValue / GM_deleteValue / GM_xmlhttpRequest 需要原生桥，当前版本未接入")
        ).also { cardBottomMargin(it) })

        b.content.addView(sectionTitle("已安装 ${scripts.size}"))
        val group = SettingGroup(ctx)
        if (scripts.isEmpty()) {
            group.addRow(ListRow(ctx).apply {
                title = "还没有脚本"
                subtitle = "点右上角 + 新建，或导入 .user.js"
            })
        }
        scripts.forEach { script ->
            val row = ListRow(ctx).apply {
                title = script.name
                subtitle = "${script.version} · ${script.matches.firstOrNull() ?: "*://*/*"}"
                showChevron(true)
                onRowClick = { edit(script) }
                setOnLongClickListener {
                    Dialogs.confirm(ctx, "删除脚本", script.name, danger = true) {
                        val activeStore = OlfactServices.scriptStore
                        if (activeStore == null) {
                            Nav.toast("脚本模块未就绪，未删除")
                            return@confirm
                        }
                        val body = try {
                            activeStore.code(script.id)
                        } catch (failure: Exception) {
                            Nav.toast("读取脚本失败，未删除：${failure.localizedMessage ?: "存储错误"}")
                            return@confirm
                        }
                        if (body == null) {
                            Nav.toast("脚本正文不可读取，未删除")
                            return@confirm
                        }
                        try {
                            if (activeStore.all().none { it.id == script.id }) {
                                Nav.toast("脚本已不存在，未删除")
                                return@confirm
                            }
                            activeStore.remove(script.id)
                            if (activeStore.all().any { it.id == script.id }) {
                                Nav.toast("删除未完成，请重试")
                                return@confirm
                            }
                        } catch (failure: Exception) {
                            Nav.toast("删除失败：${failure.localizedMessage ?: "存储错误"}")
                            return@confirm
                        }
                        render()
                        showDeletedScriptUndo(script, body)
                    }
                    true
                }
            }
            val sw = UiSwitch(ctx).apply {
                setChecked(script.enabled, false)
            }
            sw.onCheckedChange = { on ->
                val activeStore = OlfactServices.scriptStore
                if (activeStore == null) {
                    sw.setChecked(!on)
                    Nav.toast("脚本模块未就绪，状态未更改")
                } else {
                    try {
                        if (activeStore.all().none { it.id == script.id }) {
                            sw.setChecked(!on)
                            Nav.toast("脚本已不存在，状态未更改")
                        } else {
                            activeStore.setEnabled(script.id, on)
                            val saved = activeStore.all().firstOrNull { it.id == script.id }
                            if (saved?.enabled != on) {
                                sw.setChecked(!on)
                                Nav.toast("状态未保存，请重试")
                            } else {
                                Nav.toast(if (on) "已启用" else "已停用")
                            }
                        }
                    } catch (failure: Exception) {
                        sw.setChecked(!on)
                        Nav.toast("更改状态失败：${failure.localizedMessage ?: "存储错误"}")
                    }
                }
            }
            row.addTrailing(sw)
            group.addRow(row, dividerInsetDp = 0)
        }
        b.content.addView(group)
        cardBottomMargin(group)

        b.content.addView(settingGroup(
            setting("从网址安装", "支持 GreasyFork 等站点的 *.user.js 直链") {
                Dialogs.input(ctx, "脚本地址", hint = "https://greasyfork.org/scripts/xxx.user.js") { url ->
                    if (url.isBlank()) return@input
                    val source = OlfactServices.scriptStore
                    if (source == null) {
                        Nav.toast("脚本模块未接入")
                        return@input
                    }
                    Nav.toast("正在下载…")
                    source.installFromUrl(url.trim()) { ok, message ->
                        Nav.toast(if (ok) "已安装 $message" else "安装失败：$message")
                        render()
                    }
                }
            },
            setting("新建脚本", "直接写一段 JS 也可以") { edit(null) }
        ).also { cardBottomMargin(it) })

        b.content.addView(note("实现说明：脚本通过 addDocumentStartJavaScript 注入页面，注入时机由 @run-at 决定（document-body 按 document-end 处理）。GM_info 可用；GM_setValue、GM_xmlhttpRequest 等需要原生桥的 API 当前未接入，调用时会在控制台提示且不会保存任何值。"))
    }

    private fun showDeletedScriptUndo(script: UserScript, body: String) {
        val root = binding?.root ?: return
        com.google.android.material.snackbar.Snackbar
            .make(root, "已删除「${script.name}」", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
            .setAction("撤销") { restoreDeletedScript(script, body) }
            .show()
    }

    private fun restoreDeletedScript(script: UserScript, body: String) {
        val activeStore = OlfactServices.scriptStore
        if (activeStore == null) {
            showUndoRetry(script, body, "脚本模块未就绪，撤销未完成")
            return
        }
        try {
            activeStore.save(script.copy(code = body))
            if (activeStore.all().none { it.id == script.id }) {
                showUndoRetry(script, body, "撤销未完成")
                return
            }
        } catch (failure: Exception) {
            showUndoRetry(script, body, "撤销失败：${failure.localizedMessage ?: "存储错误"}")
            return
        }
        render()
        Nav.toast("已恢复「${script.name}」")
    }

    private fun showUndoRetry(script: UserScript, body: String, message: String) {
        Nav.toast("$message；正文仍保留，可重试")
        val root = binding?.root ?: return
        com.google.android.material.snackbar.Snackbar
            .make(root, message, com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
            .setAction("重试") { restoreDeletedScript(script, body) }
            .show()
    }

    private fun edit(script: UserScript?) {
        val ctx = Nav.context() ?: return
        val store = OlfactServices.scriptStore
        if (store == null) {
            Nav.toast("脚本模块未就绪，暂不能保存")
            return
        }
        val initialCode = if (script == null) DEFAULT_CODE else try {
            store.code(script.id) ?: run {
                Nav.toast("脚本正文不存在，无法编辑")
                return
            }
        } catch (failure: Exception) {
            Nav.toast("读取脚本失败：${failure.localizedMessage ?: "存储错误"}")
            return
        }
        val editor = EditText(ctx).apply {
            setText(initialCode)
            hint = "// ==UserScript=="
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6
            maxLines = 14
            gravity = Gravity.TOP or Gravity.START
            setHorizontallyScrolling(false)
            isVerticalScrollBarEnabled = true
            setSelection(text.length)
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(if (script == null) "新建脚本" else "编辑：${script.name}")
            .setView(editor)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val code = editor.text.toString()
                if (code.isBlank()) {
                    Nav.toast("脚本内容不能为空")
                    return@setOnClickListener
                }
                val activeStore = OlfactServices.scriptStore
                if (activeStore == null) {
                    Nav.toast("脚本模块未就绪，输入仍保留，请稍后重试")
                    return@setOnClickListener
                }
                val target = script ?: UserScript(
                    id = UUID.randomUUID().toString(),
                    name = "未命名脚本",
                    matches = listOf("*://*/*")
                )
                try {
                    activeStore.save(target.copy(code = code))
                } catch (failure: Exception) {
                    Nav.toast("保存失败：${failure.localizedMessage ?: "存储错误"}；输入仍保留")
                    editor.requestFocus()
                    return@setOnClickListener
                }
                AppLog.i("script", "保存脚本 ${target.name}")
                dialog.dismiss()
                Nav.toast("已保存")
                render()
            }
        }
        dialog.show()
    }

    companion object {
        private const val DEFAULT_CODE = "// 匹配：*://*/*\n(function () {\n  console.log('hello from olfact userscript');\n})();"
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
