package cn.olfact.app.ui.ai

import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import cn.olfact.ai.AiFacade
import cn.olfact.ai.summarize.ReadabilityExtractor
import cn.olfact.ai.summarize.SummaryEvent
import cn.olfact.app.data.ActiveSurface
import cn.olfact.app.nav.Nav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONTokener

/**
 * 主菜单里的两个 AI 入口：网页总结（抽正文 → 分块摘要）与划词翻译。
 *
 * 页面内容通过 [ActiveSurface] 里的渲染器 `evaluateJavascript` 取，不依赖 web 模块实现；
 * 供应商与密钥由 `AiFacade` 统一管理（Key 存在 Keystore 里，界面上只显示掩码）。
 */
object AiActions {

    /** 整页翻译取正文的上限（约 4k token 以内，避免一次请求过大）。 */
    private const val MAX_PAGE_CHARS = 6000

    fun summarize(activity: ComponentActivity) {
        if (!cn.olfact.app.data.AppSettings.get(cn.olfact.app.data.K.AI_SUMMARY)) {
            Nav.toast("已在设置里关闭「网页总结」")
            return
        }
        val ctx = prepare(activity) ?: return
        val surface = ActiveSurface.current
        if (surface == null) {
            Nav.toast("当前没有打开的网页")
            return
        }
        val sheet = AiSheet(activity, "AI 总结")
        sheet.show()
        sheet.setStatus("正在读取页面正文…")

        surface.evaluateJavascript("(function(){return document.documentElement.outerHTML})()") { raw ->
            val html = unquote(raw)
            val url = surface.url().orEmpty()
            activity.lifecycleScope.launch {
                val article = withContext(Dispatchers.Default) {
                    runCatching { ReadabilityExtractor.extract(html, url) }.getOrNull()
                }
                if (article == null || article.text.isBlank()) {
                    sheet.setStatus("这个页面没有抽到正文（可能是纯图或需要登录）")
                    return@launch
                }
                sheet.setStatus("正文 ${article.text.length} 字，开始总结…")
                runCatching {
                    ctx.facade.summarizer.summarize(ctx.provider, ctx.apiKey, article)
                        .collect { event ->
                            when (event) {
                                is SummaryEvent.Progress ->
                                    sheet.setStatus("${event.stage}（${event.index}/${event.total}）")
                                is SummaryEvent.Delta -> sheet.append(event.text)
                                is SummaryEvent.Done -> sheet.finish("完成 · ${event.result.markdown.length} 字")
                                is SummaryEvent.Failed ->
                                    sheet.finish("失败：${event.error.message ?: "未知错误"}")
                            }
                        }
                }.onFailure { sheet.finish("失败：${it.message ?: "网络错误"}") }
            }
        }
    }

    /**
     * 整页翻译：取 `document.body.innerText`（截断到 6000 字控制 token），
     * 走 [cn.olfact.ai.translate.Translator] 流式输出到面板。
     *
     * 与「划词翻译」共用同一套供应商与密钥；整页翻译更常用，所以放在长按菜单里。
     */
    fun translatePage(activity: ComponentActivity) {
        if (!cn.olfact.app.data.AppSettings.get(cn.olfact.app.data.K.AI_TRANSLATE)) {
            Nav.toast("已在设置里关闭「整页翻译」")
            return
        }
        val ctx = prepare(activity) ?: return
        val surface = ActiveSurface.current
        if (surface == null) {
            Nav.toast("当前没有打开的网页")
            return
        }
        surface.evaluateJavascript(
            "(function(){var t=document.body?document.body.innerText:'';return t?t.slice(0,$MAX_PAGE_CHARS):''})()"
        ) { raw ->
            val text = unquote(raw).trim()
            if (text.isEmpty()) {
                Nav.toast("这个页面没有可翻译的正文")
                return@evaluateJavascript
            }
            val sheet = AiSheet(activity, "AI 翻译")
            sheet.show()
            sheet.setStatus("正文 ${text.length} 字，翻译中…")
            activity.lifecycleScope.launch {
                runCatching {
                    ctx.facade.translator.translate(ctx.provider, ctx.apiKey, text)
                        .collect { delta -> sheet.append(delta) }
                    sheet.finish("完成 · 可长按复制")
                }.onFailure { sheet.finish("失败：${it.message ?: "网络错误"}") }
            }
        }
    }

    fun translateSelection(activity: ComponentActivity) {
        val ctx = prepare(activity) ?: return
        val surface = ActiveSurface.current
        if (surface == null) {
            Nav.toast("当前没有打开的网页")
            return
        }

        surface.evaluateJavascript("(function(){return String(window.getSelection())})()") { raw ->
            val selected = unquote(raw).trim()
            if (selected.isEmpty()) {
                Nav.toast("请先在页面里选中要翻译的文字")
                return@evaluateJavascript
            }
            val sheet = AiSheet(activity, "翻译")
            sheet.show()
            sheet.setStatus("选中 ${selected.length} 字，翻译中…")

            activity.lifecycleScope.launch {
                runCatching {
                    ctx.facade.translator.translate(ctx.provider, ctx.apiKey, selected)
                        .collect { delta -> sheet.append(delta) }
                    sheet.finish("完成")
                }.onFailure { sheet.finish("失败：${it.message ?: "网络错误"}") }
            }
        }
    }

    // ------------------------------------------------------------------ 公共

    private class Ctx(
        val facade: AiFacade,
        val provider: cn.olfact.ai.AiProvider,
        val apiKey: String?
    )

    /** 供应商没配就先提示去配，避免用户看到「翻译中…」然后失败。 */
    private fun prepare(activity: ComponentActivity): Ctx? {
        val facade = AiFacade.get(activity)
        val provider = facade.providers.selected()
        if (provider == null) {
            showProviderDialog(activity, "还没有 AI 供应商", "先添加一个供应商和 API Key，再回来使用总结/翻译。")
            return null
        }
        if (!facade.providers.hasApiKey(provider)) {
            showProviderDialog(activity, "供应商还缺 API Key", "「${provider.name}」还没有填 Key，填完即可使用。")
            return null
        }
        return Ctx(facade, provider, facade.providers.apiKey(provider))
    }

    /** 断头路改成直达：对话框的「去设置」直接打开 AI 供应商页。 */
    private fun showProviderDialog(activity: ComponentActivity, title: String, message: String) {
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("去设置") { _, _ ->
                Nav.push(cn.olfact.app.ui.settings.pages.AiFragment())
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** `evaluateJavascript` 回传的是 JSON 字符串字面量，去掉引号与转义。 */
    private fun unquote(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return runCatching { JSONTokener(raw).nextValue() as? String ?: raw }.getOrDefault(raw)
    }
}
