package cn.olfact.app.ui.settings.pages

import android.os.Bundle
import android.view.View
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
            b.content.addView(note("脚本模块未就绪：脚本会保存在本地，注入由浏览内核负责。"))
        }

        b.content.addView(sectionTitle("注入"))
        b.content.addView(settingGroup(
            toggle("启用 UserScript", AppSettings.get(K.SCRIPT_ENABLED), "在 document start 注入匹配脚本") { on ->
                AppSettings.set(K.SCRIPT_ENABLED, on)
            },
            toggle("GM_* 桥接", AppSettings.get(K.SCRIPT_GM_BRIDGE), "GM_setValue / GM_xmlhttpRequest 走 JsBridge") { on ->
                AppSettings.set(K.SCRIPT_GM_BRIDGE, on)
            }
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
            }
            val sw = UiSwitch(ctx).apply {
                setChecked(script.enabled, false)
                onCheckedChange = { on ->
                    store?.setEnabled(script.id, on)
                    Nav.toast(if (on) "已启用" else "已停用")
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

        b.content.addView(note("实现说明：不引入 QuickJS，脚本通过 addDocumentStartJavaScript 注入页面；GM_* 能力由 JsBridge 提供。"))
    }

    private fun edit(script: UserScript?) {
        val ctx = Nav.context() ?: return
        val store = OlfactServices.scriptStore
        Dialogs.input(ctx, if (script == null) "新建脚本" else "编辑：${script.name}", script?.code ?: DEFAULT_CODE, "// ==UserScript==", multiline = true) { code ->
            if (code.isBlank()) return@input
            val target = script ?: UserScript(
                id = UUID.randomUUID().toString(),
                name = "未命名脚本",
                matches = listOf("*://*/*")
            )
            store?.save(target.copy(code = code))
            AppLog.i("script", "保存脚本 ${target.name}")
            Nav.toast("已保存")
            render()
        }
    }

    companion object {
        private const val DEFAULT_CODE = "// 匹配：*://*/*\n(function () {\n  console.log('hello from olfact userscript');\n})();"
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
