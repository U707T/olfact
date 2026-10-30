package cn.olfact.ai.translate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import cn.olfact.ai.AiProvider
import cn.olfact.ai.error.AiError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 划词翻译浮层：选中文字 → 弹卡片 → 流式出译文 → 可复制 / 关闭。
 * 样式走代码，避免 AI 模块引入 layout 资源与主题依赖。
 */
class SelectionTranslator(
    private val context: Context,
    private val translator: Translator,
    private val providerSupplier: () -> AiProvider?,
    private val apiKeySupplier: (AiProvider) -> String?,
    private val scope: CoroutineScope,
) {

    private val main = Handler(Looper.getMainLooper())
    private var popup: PopupWindow? = null
    private var job: Job? = null
    private var bodyView: TextView? = null
    private var titleView: TextView? = null

    fun show(anchor: View, text: String, targetLanguage: String? = null) {
        val content = text.trim()
        if (content.isEmpty()) return
        val provider = providerSupplier()
        if (provider == null) {
            Toast.makeText(context, "还没有配置 AI 供应商", Toast.LENGTH_SHORT).show()
            return
        }

        dismiss()
        val card = buildCard(provider, targetLanguage)
        val window = PopupWindow(card, dp(300), LinearLayout.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            elevation = dp(8).toFloat()
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(Color.parseColor("#F21F1F1F"))
                cornerRadius = dp(14).toFloat()
            })
        }
        popup = window
        window.showAtLocation(anchor, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, dp(90))

        job = scope.launch {
            val builder = StringBuilder()
            try {
                translator.translate(provider, apiKeySupplier(provider), content, targetLanguage)
                    .collect { piece ->
                        builder.append(piece)
                        main.post { bodyView?.text = builder.toString() }
                    }
            } catch (e: AiError) {
                main.post { bodyView?.text = e.message }
            } catch (t: Throwable) {
                main.post { bodyView?.text = t.message ?: "翻译失败" }
            }
        }
    }

    fun dismiss() {
        job?.cancel()
        job = null
        popup?.dismiss()
        popup = null
        bodyView = null
        titleView = null
    }

    private fun buildCard(provider: AiProvider, targetLanguage: String?): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleView = TextView(context).apply {
            text = "${provider.name} · 翻译${if (targetLanguage != null) "（$targetLanguage）" else ""}"
            setTextColor(Color.parseColor("#CCFFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        header.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val close = TextView(context).apply {
            text = "关闭"
            setTextColor(Color.parseColor("#FF80ABFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(12), dp(4), 0, dp(4))
            setOnClickListener { dismiss() }
        }
        header.addView(close)
        card.addView(header)

        bodyView = TextView(context).apply {
            text = "翻译中…"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(0, dp(8), 0, dp(8))
        }
        card.addView(bodyView)

        val copy = TextView(context).apply {
            text = "复制译文"
            setTextColor(Color.parseColor("#FF80ABFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(4), 0, dp(4))
            setOnClickListener {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("translation", bodyView?.text ?: ""))
                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
            }
        }
        card.addView(copy)
        return card
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
