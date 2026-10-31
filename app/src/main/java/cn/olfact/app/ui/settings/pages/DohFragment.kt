package cn.olfact.app.ui.settings.pages

import android.os.Bundle
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
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
import cn.olfact.core.uikit.SettingItem

/**
 * 加密 DNS 面板（TECH-PLAN §4.5-8）。
 *
 * 数据来源优先级：B2 的 DohManager.status() → 本地配置回显（引擎未接入时）。
 * 未接入时会明确标注「配置会保存，但不会生效」，不伪造耗时/命中率。
 */
class DohFragment : Fragment(R.layout.fragment_page) {

    private var binding: FragmentPageBinding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = FragmentPageBinding.bind(view)
        binding = b
        b.header.title = "加密 DNS"
        b.header.onBack = { Nav.pop() }
        render()
    }

    private fun render() {
        val b = binding ?: return
        val ctx = requireContext()
        val manager = OlfactServices.doh
        val engineReady = manager != null
        val status: DohStatus = manager?.status() ?: DohStatus(
            enabled = AppSettings.get(K.DOH_ENABLED),
            mode = DohMode.of(AppSettings.get(K.DOH_MODE)),
            upstream = AppSettings.get(K.DOH_UPSTREAM),
            engineReady = false
        )
        b.content.removeAllViews()

        if (!engineReady) {
            b.content.addView(banner("web 模块尚未接入 DoH 引擎：下列配置会保存，但暂时不会真正生效。"))
        }

        b.content.addView(sectionTitle("状态"))
        b.content.addView(settingGroup(
            toggle("启用加密 DNS", status.enabled, "开启后按下面的分流策略走 DoH") { on ->
                AppSettings.set(K.DOH_ENABLED, on)
                // 引擎要真的跟着开关走，否则要重启应用才生效
                val engine = OlfactServices.doh
                if (engine is DohAdapter) {
                    if (on) {
                        viewLifecycleOwner.lifecycleScope.launch {
                            runCatching { engine.startFromSettings() }
                                .onFailure { Nav.toast("DoH 启动失败：${it.message}") }
                            render()
                        }
                    } else {
                        engine.stop()
                    }
                }
                render()
            },
            setting("当前上游", status.upstream),
            setting("平均解析耗时", if (status.avgCostMs > 0) "${status.avgCostMs} ms" else "—"),
            setting("缓存命中率", if (status.cacheHitRate > 0) "${status.cacheHitRate}%" else "—"),
            setting("失败次数", status.failures.toString()),
            setting("今日查询", status.queriesToday.toString()),
            setting("代理通道", if (status.proxying) "L2 已生效（WebView TCP）" else "未启用")
        ).also { cardBottomMargin(it) })

        b.content.addView(sectionTitle("分流策略"))
        b.content.addView(settingGroup(
            setting("智能分流（默认）", if (status.mode == DohMode.SMART) "已选" else null,
                "国内域名走系统 DNS，国外与清单内走 DoH") { pick(DohMode.SMART, "smart") },
            setting("仅指定域名", if (status.mode == DohMode.INCLUDE) "已选" else null,
                "默认系统 DNS，清单内走 DoH") { pick(DohMode.INCLUDE, "include") },
            setting("仅排除域名", if (status.mode == DohMode.EXCLUDE) "已选" else null,
                "默认 DoH，清单内走系统 DNS") { pick(DohMode.EXCLUDE, "exclude") }
        ).also { cardBottomMargin(it) })

        b.content.addView(sectionTitle("上游"))
        val engine = OlfactServices.doh as? DohAdapter
        val selected = (engine?.selectedUpstreamUrl() ?: AppSettings.get(K.DOH_UPSTREAM)).trim()
        val savedCustom = AppSettings.get(K.DOH_UPSTREAM_CUSTOM).trim()
        val isAuto = selected.isBlank() || selected == DohAdapter.AUTO
        val isCustom = !isAuto && !DohAdapter.isPresetUrl(selected)

        val presets = listOf(
            "https://doh.pub/dns-query" to "腾讯 DNSPod（国内）",
            "https://dns.alidns.com/dns-query" to "阿里 AliDNS（国内）",
            "https://1.1.1.1/dns-query" to "Cloudflare（国外）",
            "https://dns.quad9.net/dns-query" to "Quad9（国外）",
            "https://dns.google/dns-query" to "Google（国外）"
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

        presets.forEach { (url, name) ->
            group.addRow(ListRow(ctx).apply {
                title = name
                subtitle = url
                trailing = if (!isAuto && !isCustom && DohUpstreams.normalize(selected) == url) "当前" else null
                onRowClick = { applyUpstream(url) }
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
                    if (savedCustom.isNotBlank()) savedCustom else "",
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
                "自定义上游已生效：只使用这一个地址，不再与内置上游混跑。\n" +
                    "· 域名形式的地址仍需先解析域名（走系统 DNS）；网络 DNS 被污染时建议填 IP 形式；\n" +
                    "· 不支持 ECS（不向第三方上游暴露你的网段）。"
            } else {
                "「自动」并发探测内置上游并按延迟切换；选中某个上游后固定用它，" +
                    "只有它探测失败才回落到同池最快的一个。"
            }
        ))

        b.content.addView(sectionTitle("维护"))
        b.content.addView(settingGroup(
            setting("立即重新竞速", "并发探测全部上游，取最快") {
                val m = OlfactServices.doh
                if (m == null) Nav.toast("DoH 引擎未接入") else { m.rerace(); Nav.toast("已重新竞速") }
            },
            setting("清空 DNS 缓存") {
                val m = OlfactServices.doh
                if (m == null) Nav.toast("DoH 引擎未接入") else { m.clearCache(); Nav.toast("缓存已清空") }
            },
            setting("系统私有 DNS 设置", "兜底：让整个系统走 DoH") {
                runCatching {
                    startActivity(Intent("android.settings.PRIVATE_DNS_SETTINGS"))
                }.onFailure { Nav.toast("当前系统没有该设置页") }
            }
        ).also { cardBottomMargin(it) })

        b.content.addView(sectionTitle("分流规则"))
        val rules = EditText(ctx).apply {
            setText(AppSettings.get(K.DOH_RULES))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setBackgroundResource(R.drawable.bg_round_12)
            setPadding(24, 20, 24, 20)
            minLines = 8
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        b.content.addView(rules, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        b.content.addView(settingGroup(
            setting("保存规则") {
                AppSettings.set(K.DOH_RULES, rules.text.toString())
                Nav.toast("已保存 ${rules.text.toString().lines().count { it.isNotBlank() }} 条规则")
            },
            setting("导入 / 导出", "txt 一行一条") {
                Nav.toast("复制到剪贴板即可导出；导入走粘贴")
            }
        ).also { cardBottomMargin(it) })

        b.content.addView(note(
            "边界说明：\n" +
                "· HTTP/3（QUIC）走 UDP，ProxyController 不覆盖，L2 约覆盖 90% 流量；\n" +
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

        val engine = OlfactServices.doh as? DohAdapter
        if (engine != null && AppSettings.get(K.DOH_ENABLED)) {
            viewLifecycleOwner.lifecycleScope.launch {
                runCatching { engine.startFromSettings() }
                    .onFailure { Nav.toast("DoH 启动失败：${it.message}") }
                render()
            }
        } else {
            Nav.toast(if (target == DohAdapter.AUTO) "已切回自动竞速" else "已切换上游")
            render()
        }
    }

    private fun pick(mode: DohMode, id: String) {
        AppSettings.set(K.DOH_MODE, id)
        Nav.toast("已切换为「${if (mode == DohMode.SMART) "智能分流" else if (mode == DohMode.INCLUDE) "仅指定域名" else "仅排除域名"}」")
        render()
    }

    private fun banner(text: String) = android.widget.TextView(requireContext()).apply {
        this.text = text
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12.5f)
        setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        setBackgroundResource(R.drawable.bg_round_12)
        setPadding(24, 16, 24, 16)
        setLineSpacing(0f, 1.3f)
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
