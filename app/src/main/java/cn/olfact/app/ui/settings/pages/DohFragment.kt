package cn.olfact.app.ui.settings.pages

import android.os.Bundle
import android.content.Intent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import cn.olfact.app.R
import cn.olfact.app.data.AppSettings
import cn.olfact.app.data.DohAdapter
import cn.olfact.app.data.DohUpstreams
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
import cn.olfact.core.api.DohMode
import cn.olfact.core.api.DohStatus
import cn.olfact.core.api.OlfactServices
import cn.olfact.core.uikit.ListRow
import cn.olfact.web.doh.DohUpstream

/**
 * 加密 DNS 面板（TECH-PLAN §4.5-8）。
 *
 * 数据来源优先级：B2 的 DohManager.status() → 本地配置回显（引擎未接入时）。
 * 未接入时会明确标注「配置会保存，但不会生效」，不伪造耗时/命中率。
 */
class DohFragment : Fragment(R.layout.fragment_page) {

    private var binding: FragmentPageBinding? = null
    private var updateStatusRows: (() -> Unit)? = null

    /**
     * 规则编辑区的草稿。
     *
     * render() 会整页重建（切模式/开关都会触发），如果不记住用户正在输入的文本，
     * 一次点击就会把没保存的规则全清掉。
     */
    private var rulesDraft: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentPageBinding.bind(view)
        binding = b
        b.header.title = "加密 DNS"
        b.header.onBack = { Nav.pop() }
        render()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    updateStatusRows?.invoke()
                    delay(1_000)
                }
            }
        }
    }

    private fun render() {
        val b = binding ?: return
        val ctx = requireContext()
        val configuredMode = DohMode.of(AppSettings.get(K.DOH_MODE))
        b.content.removeAllViews()

        b.content.addView(sectionTitle("状态"))
        val enabledRow = toggle("启用加密 DNS", AppSettings.get(K.DOH_ENABLED), "开启后按下面的分流策略尝试 DoH") { on ->
            AppSettings.set(K.DOH_ENABLED, on)
            applySettings()
            updateStatusRows?.invoke()
        }
        val upstream = setting("当前上游")
        val latency = setting("平均解析耗时")
        val hitRate = setting("缓存命中率")
        val failures = setting("失败次数")
        val queries = setting("今日查询")
        val channel = setting("代理通道")
        b.content.addView(settingGroup(enabledRow, upstream, latency, hitRate, failures, queries, channel)
            .also { cardBottomMargin(it) })
        updateStatusRows = {
            val manager = OlfactServices.doh
            val status = manager?.status() ?: DohStatus()
            val requested = AppSettings.get(K.DOH_ENABLED)
            enabledRow.isCheckedItem = requested
            upstream.value = status.upstream
            latency.value = if (status.avgCostMs > 0) "${status.avgCostMs} ms" else "—"
            hitRate.value = if (status.queriesToday > 0 || status.cacheHitRate > 0) "${status.cacheHitRate}%" else "—"
            failures.value = status.failures.toString()
            queries.value = status.queriesToday.toString()
            channel.value = when {
                manager == null -> "引擎未就绪"
                status.proxying -> "L2 已挂载（WebView TCP）"
                status.enabled -> "仅应用内使用 DoH 分流"
                requested -> "引擎未运行"
                else -> "未启用"
            }
            channel.summary = if (status.enabled) (manager as? DohAdapter)?.proxyError() else null
        }
        updateStatusRows?.invoke()

        b.content.addView(sectionTitle("分流策略"))
        b.content.addView(settingGroup(
            setting("智能分流（默认）", if (configuredMode == DohMode.SMART) "已选" else null,
                "国内域名走系统 DNS，国外与清单内走 DoH") { pick(DohMode.SMART) },
            setting("仅指定域名", if (configuredMode == DohMode.INCLUDE) "已选" else null,
                "默认系统 DNS，清单内走 DoH") { pick(DohMode.INCLUDE) },
            setting("仅排除域名", if (configuredMode == DohMode.EXCLUDE) "已选" else null,
                "默认 DoH，清单内走系统 DNS") { pick(DohMode.EXCLUDE) }
        ).also { cardBottomMargin(it) })

        b.content.addView(sectionTitle("上游"))
        val engine = OlfactServices.doh as? DohAdapter
        val selected = (engine?.selectedUpstreamUrl() ?: AppSettings.get(K.DOH_UPSTREAM)).trim()
        val savedCustom = AppSettings.get(K.DOH_UPSTREAM_CUSTOM).trim()
        val isAuto = selected.isBlank() || selected.equals(DohAdapter.AUTO, ignoreCase = true)
        val selectedPreset = DohUpstream.byUrl(selected)
        val isCustom = !isAuto && selectedPreset == null

        val presets = listOf(
            DohUpstream.TENCENT to "腾讯 DNSPod（国内）",
            DohUpstream.ALI to "阿里 AliDNS（国内）",
            DohUpstream.CLOUDFLARE to "Cloudflare（国外）",
            DohUpstream.QUAD9 to "Quad9（国外）",
            DohUpstream.GOOGLE to "Google（国外）"
        )
        val group = cn.olfact.core.uikit.SettingGroup(ctx)

        // 自动：内置五路并发竞速（默认）
        group.addRow(ListRow(ctx).apply {
            title = "自动（内置竞速）"
            subtitle = "腾讯 / 阿里 / Cloudflare / Quad9 / Google，按延迟自动选"
            trailing = if (isAuto) "当前" else null
            setIcon(R.drawable.ic_refresh)
            onRowClick = { applyUpstream(DohAdapter.AUTO) }
        }, dividerInsetDp = 0)

        presets.forEach { (upstream, name) ->
            group.addRow(ListRow(ctx).apply {
                title = name
                subtitle = upstream.url
                trailing = if (selectedPreset?.id == upstream.id) "当前" else null
                onRowClick = { applyUpstream(upstream.url) }
            }, dividerInsetDp = 0)
        }

        // 自定义：任意 RFC 8484 端点；选中后只用它，不和内置混跑
        group.addRow(ListRow(ctx).apply {
            title = "自定义上游"
            subtitle = when {
                isCustom -> "$selected（仅使用该上游）"
                savedCustom.isNotBlank() -> "已保存：$savedCustom"
                else -> "https://…/dns-query，自建 AdGuard Home / NextDNS 等"
            }
            trailing = if (isCustom) "当前 · 编辑" else "设置"
            setIcon(R.drawable.ic_edit)
            onRowClick = {
                Dialogs.input(
                    ctx,
                    "自定义 DoH 上游",
                    if (isCustom) selected else savedCustom,
                    "https://dns.example.com/dns-query"
                ) { raw ->
                    when (val parsed = DohUpstreams.parse(raw)) {
                        is DohUpstreams.Result.Ok -> {
                            AppSettings.set(K.DOH_UPSTREAM_CUSTOM, parsed.url)
                            if (parsed.local) Nav.toast("本地地址：仅在可信网络使用")
                            applyUpstream(parsed.url)
                        }
                        is DohUpstreams.Result.Failed -> Nav.toast("地址不合法：${parsed.reason}")
                    }
                }
            }
        }, dividerInsetDp = 0)

        b.content.addView(group)
        cardBottomMargin(group)

        b.content.addView(note(
            if (isCustom) {
                "已选择自定义上游；DoH 查询只使用这一上游，失败时可能回落系统 DNS。\n" +
                    "· 域名形式的地址仍需先解析域名（走系统 DNS）；\n" +
                    "· 不支持 ECS（不向第三方上游暴露你的网段）。"
            } else {
                "「自动」并发探测内置上游并按延迟选择；预设在所属分池内优先，探测失败才改用其他可用上游。"
            }
        ))

        b.content.addView(sectionTitle("维护"))
        b.content.addView(settingGroup(
            setting("立即重新竞速", "并发探测全部上游，取最快") {
                val m = OlfactServices.doh
                when {
                    m == null -> Nav.toast("DoH 引擎未接入")
                    !m.status().enabled -> Nav.toast("请先启用加密 DNS")
                    else -> { m.rerace(); Nav.toast("已请求重新竞速") }
                }
            },
            setting("清空 DNS 缓存") {
                val m = OlfactServices.doh
                if (m == null) Nav.toast("DoH 引擎未接入") else { m.clearCache(); Nav.toast("缓存已清空") }
            },
            setting("系统私有 DNS 设置", "配置 Android 系统级私有 DNS（DoT）") {
                runCatching {
                    startActivity(Intent("android.settings.PRIVATE_DNS_SETTINGS"))
                }.onFailure { Nav.toast("当前系统没有该设置页") }
            }
        ).also { cardBottomMargin(it) })

        b.content.addView(sectionTitle("分流规则"))
        val rules = EditText(ctx).apply {
            setText(rulesDraft ?: AppSettings.get(K.DOH_RULES))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setBackgroundResource(R.drawable.bg_round_12)
            setPadding(24, 20, 24, 20)
            minLines = 8
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    rulesDraft = s?.toString().orEmpty()
                }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
        }
        b.content.addView(rules, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        b.content.addView(settingGroup(
            setting("保存规则") {
                val text = rules.text.toString()
                AppSettings.set(K.DOH_RULES, text)
                rulesDraft = null
                applySettings()
                Nav.toast("规则已保存")
            },
            setting("导入 / 导出", "导出到剪贴板，或从剪贴板粘贴导入") { importExport(rules) }
        ).also { cardBottomMargin(it) })

        b.content.addView(note(
            "边界说明：\n" +
                "· HTTP/3（QUIC）不经本地 TCP 隧道；需要时解析仍可能回落系统 DNS。\n" +
                "· DoH 只解决 DNS 污染/劫持，IP 层封锁与 SNI 阻断不在范围内；\n" +
                "· 不做 MITM/ECH，避免要求系统信任用户证书。"
        ))
    }

    /**
     * 选上游：落库 → 引擎在跑就立即重建（重新竞速），否则只更新界面。
     *
     * 传 [DohAdapter.AUTO] 表示回到内置竞速；其余地址一律走 [DohUpstreams] 归一化，
     * 内置地址顺手转成规范形式，避免「同一个上游因大小写/斜杠对不上而认不出」。
     */
    private fun applyUpstream(url: String) {
        val target = if (url == DohAdapter.AUTO) {
            DohAdapter.AUTO
        } else {
            when (val parsed = DohUpstreams.parse(url)) {
                is DohUpstreams.Result.Ok -> parsed.url
                is DohUpstreams.Result.Failed -> {
                    Nav.toast("地址不合法：${parsed.reason}")
                    return
                }
            }
        }
        AppSettings.set(K.DOH_UPSTREAM, target)
        applySettings()
        render()
    }

    private fun pick(mode: DohMode) {
        AppSettings.set(K.DOH_MODE, mode.id)
        applySettings()
        render()
    }

    private fun applySettings() {
        val engine = OlfactServices.doh as? DohAdapter ?: return
        val update = engine.applySettingsAsync()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                update.await()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Nav.toast("DoH 设置应用失败：${error.message}")
            }
            updateStatusRows?.invoke()
        }
    }

    /** 导入 / 导出：走剪贴板，导出的是编辑区当前内容（含未保存修改）。 */
    private fun importExport(rules: EditText) {
        val ctx = requireContext()
        Dialogs.choose(ctx, "导入 / 导出", listOf("导出到剪贴板", "从剪贴板导入"), 0) { index ->
            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager
            if (cm == null) {
                Nav.toast("当前环境没有剪贴板")
                return@choose
            }
            if (index == 0) {
                val text = rules.text.toString()
                cm.setPrimaryClip(android.content.ClipData.newPlainText("doh-rules", text))
                Nav.toast("已复制 ${text.lines().count { it.isNotBlank() }} 条规则")
            } else {
                val clip = cm.primaryClip
                val text = if (clip != null && clip.itemCount > 0) {
                    clip.getItemAt(0).coerceToText(ctx).toString()
                } else {
                    ""
                }
                if (text.isBlank()) {
                    Nav.toast("剪贴板里没有规则")
                    return@choose
                }
                rules.setText(text)
                rules.setSelection(rules.text.length)
                Nav.toast("已粘贴，点「保存规则」生效")
            }
        }
    }

    override fun onDestroyView() {
        updateStatusRows = null
        binding = null
        super.onDestroyView()
    }
}
