package cn.olfact.app.ui.settings.pages

import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import cn.olfact.app.R
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
import cn.olfact.core.api.AdBlockStats
import cn.olfact.core.api.OlfactServices
import cn.olfact.core.uikit.ListRow
import cn.olfact.core.uikit.SettingGroup

/**
 * 广告拦截面板：开关 + 计数 + 订阅 + 白名单。
 *
 * 计数优先读 B4 的 AdBlockController；未接入时读本地计数（0），并明确标注引擎状态。
 */
class AdBlockFragment : Fragment(R.layout.fragment_page) {

    private var binding: FragmentPageBinding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding = FragmentPageBinding.bind(view)
        binding!!.header.title = "广告拦截"
        binding!!.header.onBack = { Nav.pop() }
        render()
    }

    private fun render() {
        val b = binding ?: return
        val ctx = requireContext()
        val controller = OlfactServices.adBlockController
        val stats = controller?.stats() ?: AdBlockStats(
            enabled = AppSettings.get(K.AD_ENABLED),
            totalBlocked = AppSettings.get(K.AD_COUNT_TOTAL),
            todayBlocked = AppSettings.get(K.AD_COUNT_TODAY),
            ruleCount = 0,
            subscriptionCount = 0,
            engineReady = false
        )
        b.content.removeAllViews()

        b.content.addView(sectionTitle("总开关"))
        b.content.addView(settingGroup(
            toggle("启用广告拦截", stats.enabled, "基于 adblock-rust 的 uBO/ABP 语法") { on ->
                AppSettings.set(K.AD_ENABLED, on)
                controller?.setEnabled(on)
                render()
            },
            toggle("元素隐藏规则", AppSettings.get(K.AD_COSMETIC), "隐藏页面里的广告占位；关闭只拦请求") { on ->
                AppSettings.set(K.AD_COSMETIC, on)
                // 已打开的页面需要刷新才会重新注入/移除隐藏样式。
                cn.olfact.app.data.RuntimeFlags.reloadPagesOnResume = true
                render()
            }
        ).also { cardBottomMargin(it) })

        b.content.addView(sectionTitle("拦截计数"))
        b.content.addView(settingGroup(
            setting("今日拦截", stats.todayBlocked.toString()),
            setting("累计拦截", stats.totalBlocked.toString()),
            setting("生效规则", if (stats.ruleCount > 0) "${stats.ruleCount} 条" else "—"),
            setting("引擎", if (stats.engineReady) stats.engineName.ifBlank { "就绪" } else "未就绪（正在使用内置规则）")
        ).also { cardBottomMargin(it) })

        b.content.addView(sectionTitle("订阅"))
        if (controller == null) {
            b.content.addView(settingGroup(
                setting("拦截模块未就绪", "正在初始化，稍后重试") { render() }
            ).also { cardBottomMargin(it) })
            b.content.addView(note("拦截模块未初始化时不会显示任何订阅，也不接受添加或删除操作。"))
            return
        }
        val subs = controller.subscriptions()
        if (subs.isEmpty()) {
            b.content.addView(note("还没有订阅。添加一个订阅地址（例如 EasyList）后点「立即更新规则」才会真正生效。"))
        }
        val group = SettingGroup(ctx)
        subs.forEach { sub ->
            val row = ListRow(ctx).apply {
                title = sub.name
                subtitle = when {
                    sub.lastError != null -> "拉取失败：${sub.lastError}"
                    sub.ruleCount > 0 -> "${sub.ruleCount} 条规则"
                    else -> sub.url
                }
                showChevron(false)
                isClickable = true
            }
            val sw = cn.olfact.core.uikit.UiSwitch(ctx).apply {
                setChecked(sub.enabled, false)
                onCheckedChange = { on ->
                    controller.setSubscriptionEnabled(sub.id, on)
                    Nav.toast(if (on) "已启用 ${sub.name}" else "已停用 ${sub.name}")
                }
            }
            row.addTrailing(sw)
            row.setOnLongClickListener {
                Dialogs.confirm(ctx, "删除订阅", sub.name, danger = true) {
                    controller.removeSubscription(sub.id)
                    render()
                }
                true
            }
            group.addRow(row, dividerInsetDp = 0)
        }
        b.content.addView(group)
        cardBottomMargin(group)

        b.content.addView(settingGroup(
            setting("添加订阅") {
                Dialogs.input(Nav.context()!!, "订阅地址", hint = "https://…/easylist.txt") { url ->
                    if (url.isBlank()) return@input
                    val parsed = runCatching { java.net.URI(url.trim()) }.getOrNull()
                    if (parsed?.host.isNullOrBlank() || parsed.scheme?.lowercase() !in setOf("http", "https")) {
                        Nav.toast("请输入有效的 HTTP(S) 规则地址")
                        return@input
                    }
                    controller.addSubscription(url.trim().substringAfterLast('/'), url.trim())
                    Nav.toast("已添加，正在更新规则…")
                    controller.refreshAsync { ok, message ->
                        Nav.toast(if (ok) message else "更新失败：$message")
                        render()
                    }
                }
            },
            setting("立即更新规则", "拉取全部订阅并重建引擎") {
                Nav.toast("开始更新规则…")
                controller.refreshAsync { ok, message ->
                    Nav.toast(if (ok) message else "更新失败：$message")
                    render()
                }
            }
        ).also { cardBottomMargin(it) })

        b.content.addView(sectionTitle("白名单"))
        val whitelist = controller.whitelist()
        b.content.addView(settingGroup(
            setting("白名单站点", if (whitelist.isEmpty()) "无" else "${whitelist.size} 个"),
            setting("编辑白名单") {
                Dialogs.input(Nav.context()!!, "白名单", whitelist.joinToString("\n"), "一行一个域名") { text ->
                    controller.setWhitelist(text.lines().filter { it.isNotBlank() })
                    render()
                }
            }
        ).also { cardBottomMargin(it) })

        b.content.addView(note(
            "规则集以 Engine 序列化格式打包进 assets，首次 mmap 加载，避免冷启动解析十万条；" +
                "Rust 编译失败时降级到 Kotlin 匹配器（规则上限 2 万条）。"
        ))
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
