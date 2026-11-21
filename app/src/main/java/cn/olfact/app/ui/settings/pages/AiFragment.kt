package cn.olfact.app.ui.settings.pages

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import cn.olfact.app.R
import cn.olfact.app.data.AppSettings
import cn.olfact.app.data.K
import cn.olfact.app.databinding.FragmentPageBinding
import cn.olfact.app.nav.Nav
import cn.olfact.app.ui.common.cardBottomMargin
import cn.olfact.app.ui.common.note
import cn.olfact.app.ui.common.sectionTitle
import cn.olfact.app.ui.common.setting
import cn.olfact.app.ui.common.settingGroup
import cn.olfact.app.ui.common.toggle
import cn.olfact.core.api.AiProvider
import cn.olfact.core.api.OlfactServices
import cn.olfact.core.uikit.ListRow
import cn.olfact.core.uikit.SettingGroup
import cn.olfact.core.uikit.dpF
import java.util.UUID

/**
 * AI 供应商面板（N4）：OpenAI 兼容，多供应商 + 单选当前。
 * key 只存本地 SharedPreferences，界面上永远打码。
 */
class AiFragment : Fragment(R.layout.fragment_page) {

    private var binding: FragmentPageBinding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding = FragmentPageBinding.bind(view)
        binding!!.header.title = "AI 供应商"
        binding!!.header.onBack = { Nav.pop() }
        binding!!.header.addAction(R.drawable.ic_plus, "添加供应商") { edit(null) }
        render()
    }

    private fun render() {
        val b = binding ?: return
        val ctx = requireContext()
        val store = OlfactServices.aiProviders
        val providers = store?.all().orEmpty()
        val activeId = store?.activeId()
        b.content.removeAllViews()

        if (store == null) b.content.addView(note("AI 模块未就绪：供应商配置保存在本地，请求由 AI 模块发起。"))

        b.content.addView(sectionTitle("功能开关"))
        b.content.addView(settingGroup(
            toggle("网页总结", AppSettings.get(K.AI_SUMMARY), "Readability 抽正文 + 分块摘要") { AppSettings.set(K.AI_SUMMARY, it) },
            toggle("整页翻译", AppSettings.get(K.AI_TRANSLATE), "把当前页正文翻译后显示在结果面板") { AppSettings.set(K.AI_TRANSLATE, it) }
        ).also { cardBottomMargin(it) })

        b.content.addView(sectionTitle("供应商 ${providers.size}"))
        val group = SettingGroup(ctx)
        if (providers.isEmpty()) {
            group.addRow(ListRow(ctx).apply {
                title = "还没有配置供应商"
                subtitle = "支持 OpenAI / DeepSeek / 月之暗面 等兼容接口"
                onRowClick = { edit(null) }
            })
        }
        providers.forEach { p ->
            val row = ListRow(ctx).apply {
                title = p.name
                subtitle = "${p.model} · ${p.baseUrl}"
                trailing = if (p.id == activeId) "当前" else mask(store?.apiKey(p.id).orEmpty())
                showChevron(true)
                onRowClick = { edit(p) }
            }
            group.addRow(row, dividerInsetDp = 0)
        }
        b.content.addView(group)
        cardBottomMargin(group)

        b.content.addView(note("密钥用 Keystore + AES-GCM 加密后落盘；导出备份时默认剔除，需显式勾选。\n本机 Ollama / localhost 可不填 API Key；编辑时留空会保留已有 Key。"))
    }

    private fun mask(key: String): String = when {
        key.isEmpty() -> "未填 key"
        key.length <= 8 -> "••••"
        // 不回显 key 的前缀：列表里只露末 4 位足够辨认，也避免截图泄漏。
        else -> "••••" + key.takeLast(4)
    }

    private fun edit(existing: AiProvider?) {
        val ctx = Nav.context() ?: return
        val store = OlfactServices.aiProviders
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dpF(20f), ctx.dpF(8f), ctx.dpF(20f), 0)
        }
        fun field(hint: String, value: String = ""): EditText = EditText(ctx).apply {
            this.hint = hint
            setText(value)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, ctx.dpF(10f), 0, ctx.dpF(10f))
        }
        val name = field("名称，如 DeepSeek", existing?.name ?: "")
        val base = field("Base URL，如 https://api.deepseek.com/v1", existing?.baseUrl ?: "https://api.openai.com/v1")
        val model = field("模型，如 deepseek-chat", existing?.model ?: "gpt-4o-mini")
        // 编辑时不再把明文 key 填进输入框：留空即保持原 key（适配层 apiKey 为 null 时不覆盖）。
        val key = field(if (existing == null) "API Key" else "API Key（留空保持不变）", "").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        listOf(name, base, model, key).forEach { container.addView(it) }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(if (existing == null) "添加供应商" else "编辑供应商")
            .setView(container)
            .setPositiveButton("保存", null)
            .setNeutralButton(if (existing == null) "取消" else "删除", null)
            .setNegativeButton(if (existing == null) null else "设为当前", null)
            .create()
            .apply editorDialog@{
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val providerName = name.text.toString().trim()
                        val baseUrl = base.text.toString().trim().trimEnd('/')
                        val modelName = model.text.toString().trim()
                        if (providerName.isBlank() || baseUrl.isBlank() || modelName.isBlank()) {
                            Nav.toast("名称、Base URL 与模型必填")
                            return@setOnClickListener
                        }
                        val activeStore = store
                        if (activeStore == null) {
                            Nav.toast("AI 供应商模块未就绪，请稍后重试")
                            return@setOnClickListener
                        }
                        val provider = AiProvider(
                            id = existing?.id ?: UUID.randomUUID().toString(),
                            name = providerName,
                            baseUrl = baseUrl,
                            apiKey = key.text.toString(),
                            model = modelName,
                            enabled = existing?.enabled ?: true
                        )
                        runCatching {
                            activeStore.save(provider)
                            if (activeStore.activeId() == null) activeStore.setActive(provider.id)
                        }.onSuccess {
                            dismiss()
                            render()
                        }.onFailure {
                            Nav.toast("保存失败：${it.message ?: "存储错误"}")
                        }
                    }

                    getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        if (existing == null) {
                            dismiss()
                            return@setOnClickListener
                        }
                        val activeStore = store
                        if (activeStore == null) {
                            Nav.toast("AI 供应商模块未就绪，请稍后重试")
                            return@setOnClickListener
                        }
                        val confirm = AlertDialog.Builder(ctx)
                            .setTitle("删除供应商")
                            .setMessage("将删除「${existing.name}」及其 API Key，不可恢复。")
                            .setPositiveButton("删除", null)
                            .setNegativeButton("取消", null)
                            .create()
                            .apply {
                                setOnShowListener {
                                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                                        runCatching { activeStore.remove(existing.id) }
                                            .onSuccess {
                                                dismiss()
                                                this@editorDialog.dismiss()
                                                render()
                                            }
                                            .onFailure {
                                                Nav.toast("删除失败：${it.message ?: "存储错误"}")
                                            }
                                    }
                                }
                            }
                        confirm.show()
                    }

                    if (existing != null) {
                        getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                            val activeStore = store
                            if (activeStore == null) {
                                Nav.toast("AI 供应商模块未就绪，请稍后重试")
                                return@setOnClickListener
                            }
                            runCatching { activeStore.setActive(existing.id) }
                                .onSuccess {
                                    dismiss()
                                    render()
                                }
                                .onFailure {
                                    Nav.toast("设置当前供应商失败：${it.message ?: "存储错误"}")
                                }
                        }
                    }
                }
            }
        dialog.show()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
